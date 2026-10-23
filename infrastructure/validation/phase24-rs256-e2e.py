#!/usr/bin/env python3
"""Disposable Phase 24 validation. Requires packaged jars, Docker and JDK 25.
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
RUN = 'phase24-' + uuid.uuid4().hex[:10]
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
ports = {s: port() for s in ['auth', 'pricing', 'quote', 'audit', 'api-gateway']}
# Ephemeral external keys: only Auth receives the private-key configuration.
private_key = LOG/'jwt-private.pem'
public_key = LOG/'jwt-public.pem'
cmd('openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(private_key))
private_key.chmod(0o600)
cmd('openssl','pkey','-in',str(private_key),'-pubout','-out',str(public_key))
configured = {}

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
               QUOTE_EXPIRATION_POLL_INTERVAL='500', OUTBOX_PUBLISHER_ENABLED='true',
               PRICING_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), PRICING_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase24-disposable')
    env.pop('AUTH_JWT_PRIVATE_KEY',None)
    if service == 'auth':env['AUTH_JWT_PRIVATE_KEY']='file:'+str(private_key)
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
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase24-disposable','-p','127.0.0.1::5432'])
    pgport=int(cmd('docker','port',pg,'5432/tcp').rsplit(':',1)[1])
    wait(lambda: sql('postgres','select 1')=='1')
    for db in ['auth_db','pricing_db','quote_db','audit_db']: sql('postgres','CREATE DATABASE '+db)
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
    for service in ['auth','pricing','quote','audit','api-gateway']: start(service,publish=True)
    import urllib.error
    from http.cookiejar import CookieJar
    import hmac
    import hashlib
    gateway=f'http://localhost:{ports["api-gateway"]}'
    def http(method,path,body=None,token=None,base=gateway,client=None):
        headers={'Content-Type':'application/json','X-Correlation-ID':'phase24-e2e'}
        if token: headers['Authorization']='Bearer '+token
        request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
        try:
            with (client or urllib.request.build_opener()).open(request,timeout=10) as response:
                result=json.loads(response.read() or b'null')
                if base==gateway:assert response.headers.get('X-Correlation-ID')=='phase24-e2e'
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
    credentials,customer=register('phase24-customer@example.invalid')
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
    admin_credentials,admin=register('phase24-admin@example.invalid')
    sql('auth_db',"UPDATE users SET role='ADMIN' WHERE id='"+admin['id']+"'")
    status,admin_login=http('POST','/api/v1/auth/login',admin_credentials);assert status==200
    admin_token=admin_login['accessToken'];rs256(admin_token)
    routes={'auth':'/api/v1/admin/customers','quote':'/api/v1/quotes',
            'pricing':'/api/v1/admin/pricing/rules','audit':'/api/v1/admin/audit'}
    for service,path in routes.items():
        for base in [gateway,f'http://localhost:{ports[service]}']:
            assert http('GET',path,token=admin_token,base=base)[0]==200,(service,path)
            if service!='quote':assert http('GET',path,token=customer_token,base=base)[0]==403
    header,payload,_=admin_token.split('.')
    enc=lambda value:base64.urlsafe_b64encode(value).decode().rstrip('=')
    data=enc(b'{"alg":"HS256"}')+'.'+payload
    hs256=data+'.'+enc(hmac.new(public_key.read_bytes(),data.encode(),hashlib.sha256).digest())
    none=enc(b'{"alg":"none"}')+'.'+payload+'.'
    unrelated=LOG/'unrelated-private.pem'
    cmd('openssl','genpkey','-algorithm','RSA','-pkeyopt','rsa_keygen_bits:2048','-out',str(unrelated))
    unrelated.chmod(0o600)
    message=LOG/'message';signature=LOG/'signature';message.write_text(header+'.'+payload)
    cmd('openssl','dgst','-sha256','-sign',str(unrelated),'-out',str(signature),str(message))
    wrong=header+'.'+payload+'.'+enc(signature.read_bytes())
    for service,path in routes.items():
        for base in [gateway,f'http://localhost:{ports[service]}']:
            for token in [wrong,hs256,none,'malformed']:
                status,result=http('GET',path,token=token,base=base)
                assert status==401,(service,status)
                assert token not in json.dumps(result)
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
    print('PASS: disposable PostgreSQL/Kafka, real Auth/Gateway/Quote/Pricing/Audit; login and refresh RS256 verified with public key; rotation/replay unchanged; CUSTOMER and ADMIN routes; gateway and direct backend validation; wrong RSA, HS256/public-key confusion, none and malformed all 401; correlation preserved; no private material in logs, responses or frontend; verifier jars contain no private key.')
    print('private key configured in Auth: yes')
    print('public key configured in verifier services: yes')
    print('verifier process environments require/receive private key: no')
    print('Logs:',LOG)
finally:
    for service in list(processes):stop(service)
    for name in reversed(containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
    # Ephemeral signing keys are removed after validation; logs contain no key material.
    for name in ['jwt-private.pem','unrelated-private.pem']:
        (LOG/name).unlink(missing_ok=True)
