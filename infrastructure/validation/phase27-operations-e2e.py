#!/usr/bin/env python3
"""Disposable Phase 27 validation. Requires packaged jars, Docker and JDK 25.
No shared volumes, databases, topics, or group offsets are touched.
"""
import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
RUN = 'phase27-' + uuid.uuid4().hex[:10]
LOG = Path(tempfile.mkdtemp(prefix=RUN))
containers = []
processes = {}

def cmd(*args, data=None):
    return subprocess.run(args, input=data, text=True, capture_output=True, check=True).stdout.strip()

def port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

def wait(check, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if check(): return
        except Exception:
            pass
        time.sleep(.3)
    raise AssertionError('Timed out; inspect ' + str(LOG))

def run_container(name, image, args):
    containers.append(name)
    return cmd('docker', 'run', '-d', '--name', name, *args, image)

pg = RUN + '-postgres'
kafka = RUN + '-kafka'
kport = port()
ports = {s: port() for s in ['auth', 'pricing', 'quote', 'audit', 'notification', 'api-gateway']}
# Ephemeral external keys: only Auth receives the private-key configuration.
private_key = LOG/'jwt-private.pem'
public_key = LOG/'jwt-public.pem'
cmd('openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private_key))
private_key.chmod(0o600)
cmd('openssl','pkey','-in',str(private_key),'-pubout','-out',str(public_key))
configured = {}
browser_check = os.environ.get('PHASE27_BROWSER_CHECK') == 'true'
if browser_check: ports['api-gateway'] = 8080

def browser_stage(name):
    if browser_check:
        (LOG/(name+'.ready')).touch()
        print('BROWSER STAGE '+name+' '+str(LOG), flush=True)
        wait(lambda: (LOG/(name+'.continue')).exists(), timeout=240)

service_secret = uuid.uuid4().hex + uuid.uuid4().hex


def sql(db, query):
    return cmd('docker','exec',pg,'psql','-h','127.0.0.1','-U','test','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',query)

def kafka_cmd(tool, *args, data=None):
    return cmd('docker','exec','-i',kafka,'/opt/kafka/bin/'+tool,'--bootstrap-server','localhost:19092',*args,data=data)

def start(service, publish=False):
    env = dict(os.environ, SERVER_PORT=str(ports[service]), AUTH_JWT_PUBLIC_KEY="file:"+str(public_key), SPRING_PROFILES_ACTIVE="validation",
               AUTH_REFRESH_COOKIE_SECURE="false",
               AUTH_SERVICE_URL='http://localhost:'+str(ports['auth']),
               PRICING_SERVICE_URL='http://localhost:'+str(ports['pricing']),
               SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:'+str(kport),
               AUTH_OUTBOX_PUBLISHER_ENABLED='true',
               AUDIT_SERVICE_URL='http://localhost:'+str(ports['audit']),
               QUOTE_SERVICE_URL='http://localhost:'+str(ports['quote']),
               NOTIFICATION_SERVICE_URL='http://localhost:'+str(ports['notification']), OPERATIONS_HEALTH_TIMEOUT_MS='1000',
               QUOTE_EXPIRATION_POLL_INTERVAL='500', OUTBOX_PUBLISHER_ENABLED='true',
               PRICING_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), PRICING_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_HIKARI_CONNECTIONTIMEOUT='1000', SPRING_DATASOURCE_HIKARI_VALIDATIONTIMEOUT='500', SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase27-disposable')
    env.pop('AUTH_JWT_PRIVATE_KEY',None)
    env.pop('QUOTE_SERVICE_CLIENT_SECRET', None)
    if service in ['auth', 'quote']:
        env['QUOTE_SERVICE_CLIENT_SECRET'] = service_secret
        env['QUOTE_SERVICE_CLIENT_ID'] = 'quote-service'
    env.update(OUTBOX_METRICS_INTERVAL='500', PRICING_CONNECT_TIMEOUT='200ms', PRICING_READ_TIMEOUT='300ms', AUTH_TOKEN_CONNECT_TIMEOUT='200ms', AUTH_TOKEN_READ_TIMEOUT='300ms', PRICING_CB_MIN_CALLS='3', PRICING_CB_SLIDING_WINDOW_SIZE='3', PRICING_CB_OPEN_WAIT='2s')
    env['SERVICE_TOKEN_TTL'] = '8s'
    env['SERVICE_TOKEN_EXPIRY_MARGIN'] = '1s'
    if service == 'quote': env['SERVICE_TOKEN_URL'] = f'http://localhost:{ports["auth"]}/api/v1/auth/service-token'
    if service == 'auth':env['AUTH_JWT_PRIVATE_KEY']='file:'+str(private_key)
    assert (service in ['auth', 'quote']) == ('QUOTE_SERVICE_CLIENT_SECRET' in env)
    configured[service] = {key: env.get(key) for key in ['AUTH_JWT_PRIVATE_KEY','AUTH_JWT_PUBLIC_KEY']}
    if service != 'api-gateway':
        env['SPRING_DATASOURCE_URL']=f'jdbc:postgresql://localhost:{pgport}/{service}_db'
    jar = ROOT / 'services' / (service+'-service' if service != 'api-gateway' else service) / 'target' / (service+'-service-0.0.1-SNAPSHOT.jar' if service != 'api-gateway' else service+'-0.0.1-SNAPSHOT.jar')
    out = open(LOG/(service+('-publisher' if publish else '')+'.log'),'w')
    processes[service]=subprocess.Popen(['java','-jar',str(jar)],env=env,stdout=out,stderr=subprocess.STDOUT)
    def healthy():
        with urllib.request.urlopen(f'http://localhost:{ports[service]}/actuator/health',timeout=2) as r:
            return r.status == 200
    wait(healthy)

def stop(service):
    p=processes.pop(service)
    p.terminate()
    try: p.wait(timeout=15)
    except subprocess.TimeoutExpired: p.kill();p.wait()

def ends(topic="insurance.pricing.events"):
    lines=kafka_cmd('kafka-get-offsets.sh','--topic',topic).splitlines()
    return sum(int(line.rsplit(':',1)[1]) for line in lines if line.startswith(topic+':'))

try:
    pgport=port()
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase27-disposable','-p',f'127.0.0.1:{pgport}:5432'])
    pgport=int(cmd('docker','port',pg,'5432/tcp').rsplit(':',1)[1])
    wait(lambda: sql('postgres','select 1')=='1')
    for db in ['auth_db','pricing_db','quote_db','audit_db','notification_db']: sql('postgres','CREATE DATABASE '+db)
    settings={
        'CLUSTER_ID':'MkU3OEVBNTcwNTJENDM2Qk','KAFKA_NODE_ID':'1','KAFKA_PROCESS_ROLES':'broker,controller',
        'KAFKA_LISTENERS':'INTERNAL://:19092,EXTERNAL://:9092,CONTROLLER://:9093',
        'KAFKA_ADVERTISED_LISTENERS':f'INTERNAL://localhost:19092,EXTERNAL://localhost:{kport}',
        'KAFKA_LISTENER_SECURITY_PROTOCOL_MAP':'INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT',
        'KAFKA_INTER_BROKER_LISTENER_NAME':'INTERNAL','KAFKA_CONTROLLER_LISTENER_NAMES':'CONTROLLER',
        'KAFKA_CONTROLLER_QUORUM_VOTERS':'1@localhost:9093','KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR':'1',
        'KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS':'0','KAFKA_AUTO_CREATE_TOPICS_ENABLE':'false'}
    args=['-p',f'127.0.0.1:{kport}:9092']
    for key,value in settings.items(): args += ['-e',key+'='+value]
    run_container(kafka,'apache/kafka:4.1.2',args)
    wait(lambda: 'insurance' not in kafka_cmd('kafka-topics.sh','--list'))
    for topic in ['insurance.user.events','insurance.user.events.dlq','insurance.quote.events','insurance.quote.events.dlq','insurance.pricing.events','insurance.pricing.events.dlq']:
        kafka_cmd('kafka-topics.sh','--create','--topic',topic,'--partitions','3','--replication-factor','1')
    for service in ['auth','pricing','quote','audit','notification','api-gateway']: start(service,publish=True)
    import urllib.error
    from http.cookiejar import CookieJar
    import hmac
    import hashlib
    gateway=f'http://localhost:{ports["api-gateway"]}'
    def http(method,path,body=None,token=None,base=gateway,client=None):
        headers={'Content-Type':'application/json','X-Correlation-ID':'phase27-e2e'}
        if token: headers['Authorization']='Bearer '+token
        request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
        try:
            with (client or urllib.request.build_opener()).open(request,timeout=10) as response:
                result=json.loads(response.read() or b'null')
                if base==gateway:assert response.headers.get('X-Correlation-ID')=='phase27-e2e'
                assert 'PRIVATE KEY' not in json.dumps(result)
                return response.status,result
        except urllib.error.HTTPError as failure:
            result=json.loads(failure.read() or b'null')
            assert 'Exception' not in json.dumps(result) and 'PRIVATE KEY' not in json.dumps(result)
            return failure.code,result
    def register(email):
        credentials=dict(email=email,password='Disposable-test-password-42!')
        status,user=http('POST','/api/v1/auth/register',dict(**credentials,firstName='Phase',lastName='Test'))
        assert status==201
        return credentials,user
    def rs256(token):
        header,payload,signature=token.split('.')
        assert json.loads(base64.urlsafe_b64decode(header+'=='))['alg']=='RS256'
        message=LOG/'message';signed=LOG/'signature'
        message.write_bytes((header+'.'+payload).encode());signed.write_bytes(base64.urlsafe_b64decode(signature+'=='))
        cmd('openssl','dgst','-sha256','-verify',str(public_key),'-signature',str(signed),str(message))
    jar=CookieJar();client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    credentials,customer=register('phase27-customer@example.invalid')
    status,login=http('POST','/api/v1/auth/login',credentials,client=client);assert status==200
    customer_token=login['accessToken'];rs256(customer_token)
    old_cookie=next(iter(jar)).value
    assert http('GET','/api/v1/vehicles/brands',token=customer_token)[0]==200
    assert http('GET','/api/v1/quotes',token=customer_token)[0]==200
    status,renewed=http('POST','/api/v1/auth/refresh',{},client=client);assert status==200
    rs256(renewed['accessToken']);assert next(iter(jar)).value!=old_cookie
    # Login-issued and refreshed tokens work independently at Quote.
    assert http('GET','/api/v1/quotes',token=renewed['accessToken'],base=f'http://localhost:{ports["quote"]}')[0]==200
    replay=urllib.request.build_opener()
    replay.addheaders=[('Cookie','refresh_token='+old_cookie)]
    assert http('POST','/api/v1/auth/refresh',{},client=replay)[0]==401
    admin_credentials,admin=register('phase27-admin@example.invalid')
    sql('auth_db',"UPDATE users SET role='ADMIN' WHERE id='"+admin['id']+"'")
    status,admin_login=http('POST','/api/v1/auth/login',admin_credentials);assert status==200
    admin_token=admin_login['accessToken'];rs256(admin_token)
    routes={'auth':'/api/v1/admin/customers','quote':'/api/v1/quotes',
            'pricing':'/api/v1/admin/pricing/rules','audit':'/api/v1/admin/audit'}
    for service,path in routes.items():
        for base in [gateway,f'http://localhost:{ports[service]}']:
            assert http('GET',path,token=admin_token,base=base)[0]==200,(service,path)
            if service!='quote':assert http('GET',path,token=customer_token,base=base)[0]==403
    calculation=dict(dateOfBirth='1995-04-20',drivingExperienceYears=8,vehicleManufacturingYear=2021,
                     vehicleValue=700000,engineSizeCc=1500,previousClaimsCount=1,coverageType='COMPREHENSIVE')
    quote_body=dict(calculation,vehicleBrandId='f0a67386-c7ae-562e-ae46-9fe3042cf38e',vehicleModelId='7966e042-1127-5b17-8e0c-a6799b4e67d0')
    before=int(sql('quote_db','select count(*) from quotes'))
    for i in range(3):
        status,quote=http('POST','/api/v1/quotes',quote_body,token=customer_token)
        assert status==201,(status,quote)
    assert int(sql('quote_db','select count(*) from quotes'))==before+3
    wait(lambda: int(sql('quote_db', "select count(*) from outbox_events where event_type='QuotePriced'"))>=3)
    operation='/api/v1/admin/operations'
    assert http('GET',operation)[0]==401
    assert http('GET',operation,token=customer_token)[0]==403
    assert http('GET',operation+'/proxy/env',token=admin_token)[0]==404
    def overview():
        status,result=http('GET',operation,token=admin_token)
        assert status==200,(status,result)
        assert len(result['services'])==6
        assert {s['id'] for s in result['services']}=={'api-gateway','auth-service','quote-service','pricing-service','notification-service','audit-service'}
        encoded=json.dumps(result)
        for forbidden in ['Authorization','localhost','jdbc:','measurements','availableTags',service_secret,customer_token,admin_token,'PRIVATE KEY']:
            assert forbidden not in encoded
        return result
    result=overview()
    assert result['overallStatus']=='UP',result
    assert all(s['status']=='UP' and s['readiness']=='READY' for s in result['services']),result
    cards={s['id']:s for s in result['services']}
    assert cards['quote-service']['metrics']['quoteCreated']==3
    assert cards['pricing-service']['metrics']['circuitBreakerState']=='CLOSED'
    assert cards['pricing-service']['metrics']['pricingAvgLatencyMs']>0
    def consumed():
        cards={s['id']:s for s in overview()['services']}
        return all((cards[s]['metrics']['processedEvents'] or 0)>=3 for s in ['notification-service','audit-service']) and (cards['quote-service']['metrics']['outboxPublished'] or 0)>=3
    wait(consumed)
    count=sql('quote_db','select count(*) from quotes')
    outbox_count=sql('quote_db','select count(*) from outbox_events')
    browser_stage('healthy')
    stop('pricing')
    result=overview()
    cards={s['id']:s for s in result['services']}
    assert result['overallStatus']=='DEGRADED'
    assert cards['pricing-service']['status']=='DOWN'
    assert all(s['status']=='UP' for s in result['services'] if s['id']!='pricing-service'),result
    for _ in range(3): assert http('POST','/api/v1/quotes',quote_body,token=customer_token)[0]==503
    cards={s['id']:s for s in overview()['services']}
    assert cards['pricing-service']['metrics']['circuitBreakerState']=='OPEN',cards
    assert cards['pricing-service']['metrics']['pricingCallErrors']>=3
    assert sql('quote_db','select count(*) from quotes')==count
    assert sql('quote_db','select count(*) from outbox_events')==outbox_count
    browser_stage('outage')
    start('pricing',publish=True)
    wait(lambda: overview()['overallStatus']=='UP')
    wait(lambda: http('POST','/api/v1/quotes',quote_body,token=customer_token)[0]==201)
    cards={s['id']:s for s in overview()['services']}
    assert cards['pricing-service']['status']=='UP'
    assert cards['pricing-service']['metrics']['circuitBreakerState']=='CLOSED'
    browser_stage('recovered')
    print('PASS: one ADMIN operations API; CUSTOMER 403 and anonymous 401; exactly six healthy cards; curated runtime metrics; Pricing DOWN and platform DEGRADED with other cards UP; OPEN circuit telemetry from Quote; no partial quote/outbox; Pricing restored UP/CLOSED; safe fixed DTO.', flush=True)
    for log in LOG.glob('*.log'):
        content=log.read_text()
        for sensitive in [service_secret,customer_token,admin_token]: assert sensitive not in content
    assert configured['auth']['AUTH_JWT_PRIVATE_KEY']
    for service in ['api-gateway','quote','pricing','audit']:
        assert configured[service]['AUTH_JWT_PRIVATE_KEY'] is None
        assert configured[service]['AUTH_JWT_PUBLIC_KEY']
    # Verify packaged verifiers contain no private fixture and source/frontend contain no signing config.
    import zipfile
    for service in ['api-gateway','quote','pricing','audit']:
        name=service+'-service' if service!='api-gateway' else service
        with zipfile.ZipFile(ROOT/'services'/name/'target'/(name+'-0.0.1-SNAPSHOT.jar')) as archive:
            assert not any('private.pem' in entry for entry in archive.namelist())
    for path in (ROOT/'frontend/src').rglob('*'):
        if path.is_file():assert b'PRIVATE KEY' not in path.read_bytes()
    for path in LOG.glob('*.log'):
        content=path.read_text()
        assert private_key.read_text().splitlines()[1] not in content
        assert 'BEGIN PRIVATE KEY' not in content and old_cookie not in content
    print('PASS: RS256 login/refresh regression and log redaction; no private signing material in verifier jars/environments.')
    print('Logs:',LOG)
finally:
    for service in list(processes):stop(service)
    for name in reversed(containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
    # Ephemeral signing keys are removed after validation; logs contain no key material.
    for name in ['jwt-private.pem']:
        (LOG/name).unlink(missing_ok=True)
