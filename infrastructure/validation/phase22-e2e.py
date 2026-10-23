#!/usr/bin/env python3
"""Disposable Phase 22 validation. Requires packaged jars, Docker and JDK 25.
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
RUN = 'phase22-' + uuid.uuid4().hex[:10]
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
secret = base64.b64encode(os.urandom(32)).decode()

def sql(db, query):
    return cmd('docker','exec',pg,'psql','-h','127.0.0.1','-U','test','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',query)

def kafka_cmd(tool, *args, data=None):
    return cmd('docker','exec','-i',kafka,'/opt/kafka/bin/'+tool,'--bootstrap-server','localhost:19092',*args,data=data)

def start(service, publish=False):
    env = dict(os.environ, SERVER_PORT=str(ports[service]), AUTH_JWT_PUBLIC_KEY="file:"+str(ROOT/"services/auth-service/src/main/resources/keys/dev-jwt-public.pem"),
               AUTH_SERVICE_URL='http://localhost:'+str(ports['auth']),
               PRICING_SERVICE_URL='http://localhost:'+str(ports['pricing']),
               SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:'+str(kport),
               AUTH_OUTBOX_PUBLISHER_ENABLED='true',
               AUDIT_SERVICE_URL='http://localhost:'+str(ports['audit']),
               QUOTE_SERVICE_URL='http://localhost:'+str(ports['quote']),
               QUOTE_EXPIRATION_POLL_INTERVAL='500', OUTBOX_PUBLISHER_ENABLED='true',
               PRICING_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), PRICING_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase22-disposable')
    env.pop('AUTH_JWT_PRIVATE_KEY',None)
    if service == 'auth':env['AUTH_JWT_PRIVATE_KEY']='file:'+str(ROOT/'services/auth-service/src/main/resources/keys/dev-jwt-private.pem')
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
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase22-disposable','-p','127.0.0.1::5432'])
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
    from concurrent.futures import ThreadPoolExecutor
    correlation='phase22-e2e-'+uuid.uuid4().hex
    gateway=f'http://localhost:{ports["api-gateway"]}'
    def http(method,path,body=None,token=None,cid=correlation,base=gateway):
        headers={'Content-Type':'application/json','X-Correlation-ID':cid}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
        try:
            with urllib.request.urlopen(request,timeout=10) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as failure:return failure.code,json.load(failure)
    credentials=dict(email='phase22-admin@example.com',password='Disposable-test-password-42!')
    status,admin=http('POST','/api/v1/auth/register',dict(**credentials,firstName='Test',lastName='Admin'));assert status==201
    sql('auth_db',"update users set role='ADMIN' where id='"+admin['id']+"'")
    status,login=http('POST','/api/v1/auth/login',credentials);assert status==200
    token=login['accessToken']
    customer_credentials=dict(email='phase22-customer@example.com',password='Disposable-test-password-42!')
    status,customer=http('POST','/api/v1/auth/register',dict(**customer_credentials,firstName='Test',lastName='Customer'));assert status==201
    status,login=http('POST','/api/v1/auth/login',customer_credentials);assert status==200
    customer_token=login['accessToken']
    status,brands=http('GET','/api/v1/vehicles/brands',token=customer_token);assert status==200
    brand=brands[0]['id']
    status,models=http('GET','/api/v1/vehicles/brands/'+brand+'/models',token=customer_token);assert status==200
    quote_body=dict(dateOfBirth='1995-04-20',drivingExperienceYears=8,previousClaimsCount=1,vehicleBrandId=brand,
        vehicleModelId=models[0]['id'],vehicleManufacturingYear=2021,vehicleValue=700000,engineSizeCc=1500,coverageType='COMPREHENSIVE')
    status,quote=http('POST','/api/v1/quotes',quote_body,customer_token);assert status==201,(status,quote)
    # Only the disposable quote fixture is backdated to exercise the existing expiration scheduler.
    sql('quote_db',"update quotes set expires_at=now()-interval '1 second' where id='"+quote['id']+"'")
    target='/api/v1/admin/customers/'+customer['id']
    assert http('PATCH',target+'/disable',token=token)[0]==200
    assert http('PATCH',target+'/enable',token=token)[0]==200
    path='/api/v1/admin/pricing/rules'
    rule_body=dict(ruleType='DRIVER_AGE',operator='LESS_THAN',comparisonValue='21',comparisonValueTo=None,
                   factor=1.30,fixedAmount=None,effectiveFrom='2030-01-01T00:00:00Z',enabled=True,description='Disposable rule')
    status,rule=http('POST',path,rule_body,token);assert status==201
    status,rule=http('PUT',path+'/'+rule['id'],dict(rule_body,comparisonValue='22',version=rule['version']),token);assert status==200
    status,rule=http('PATCH',path+'/'+rule['id']+'/disable',dict(version=rule['version']),token);assert status==200
    assert http('PATCH',path+'/'+rule['id']+'/enable',dict(version=rule['version']),token)[0]==200
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='10')
    before=sql('audit_db','select row_to_json(r)::text from audit_records r order by id')
    path='/api/v1/admin/audit'
    for base in [gateway,f'http://localhost:{ports["audit"]}']:
        for suffix in ['', '/'+str(uuid.uuid4())]:
            assert http('GET',path+suffix,base=base)[0]==401
            assert http('GET',path+suffix,token=customer_token,base=base)[0]==403
        assert http('POST',path,{},token,base=base)[0]==403
    status,page=http('GET',path,token=token);assert status==200 and page['totalElements']==10
    types={r['eventType'] for r in page['content']}
    assert types=={'UserRegistered','QuotePriced','QuoteExpired','UserDisabled','UserEnabled','PricingRuleCreated','PricingRuleUpdated','PricingRuleDisabled','PricingRuleEnabled'},types
    ordering=[(r['occurredAt'],r['id']) for r in page['content']];assert ordering==sorted(ordering,reverse=True)
    assert all('metadata' not in r for r in page['content'])
    assert http('GET',path+'?page=1&size=3',token=token)[1]['content']==page['content'][3:6]
    for event_type in types:
        status,filtered=http('GET',path+'?eventType='+event_type,token=token)
        assert status==200 and all(r['eventType']==event_type for r in filtered['content'])
    for query,expected in [('actorType=ADMIN',6),('actorType=CUSTOMER',3),('entityType=PricingRule',4),
                           ('actorId='+admin['id'],7),('entityId='+customer['id'],3),('producer=pricing-service',4),
                           ('correlationId='+correlation,9),('correlationId=does-not-exist',0),
                           ('actorType=ADMIN&entityType=User&producer=auth-service',2),
                           ('from=2000-01-01T00:00:00Z&to=2100-01-01T00:00:00Z',10)]:
        status,filtered=http('GET',path+'?'+query,token=token);assert status==200 and filtered['totalElements']==expected,(query,filtered)
    for row in page['content']:
        status,detail=http('GET',path+'/'+row['id'],token=token);assert status==200
        assert isinstance(detail['metadata'],dict)
        assert not any(field in json.dumps(detail['metadata']) for field in ['password','token','headers','email'])
        assert http('GET',path+'?eventId='+row['eventId'],token=token)[1]['totalElements']==1
    assert http('GET',path+'/'+str(uuid.uuid4()),token=token)[0]==404
    assert http('GET',path+'?size=101',token=token)[0]==400
    assert sql('audit_db','select row_to_json(r)::text from audit_records r order by id')==before
    evidence=['Phase 22 disposable Auth/Pricing/Quote → Kafka → Audit → Gateway browsing: PASS',
        '10 real audit records; all nine event types; includes scheduled QuoteExpired from a backdated disposable quote.',
        'ADMIN list/detail, default and explicit pagination, newest occurrence ordering, exact event/actor/entity/producer/correlation/eventId and combined/date filters: PASS.',
        'Gateway and direct Audit independently reject missing JWT (401), CUSTOMER (403), and mutation methods (403).',
        'Unknown detail 404, oversized page 400, compact lists, JSON metadata without email/credentials/headers: PASS.',
        'Audit rows byte-equivalent before/after all reads. No shared databases, volumes, topics or audit history touched.',
        'Angular UI validated separately by component/HTTP/store/guard tests and production build; this harness exercises real APIs.']
    (LOG/'evidence.txt').write_text('\n'.join(evidence));print('\n'.join(evidence));print('Logs:',LOG)
finally:
    for service in list(processes):stop(service)
    for name in reversed(containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
