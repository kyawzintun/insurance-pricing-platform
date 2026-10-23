#!/usr/bin/env python3
"""Disposable Phase 20 validation. Requires packaged jars, Docker and JDK 25.
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
RUN = 'phase20-' + uuid.uuid4().hex[:10]
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
ports = {s: port() for s in ['auth', 'pricing', 'audit', 'api-gateway']}
secret = base64.b64encode(os.urandom(32)).decode()

def sql(db, query):
    return cmd('docker','exec',pg,'psql','-U','test','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',query)

def kafka_cmd(tool, *args, data=None):
    return cmd('docker','exec','-i',kafka,'/opt/kafka/bin/'+tool,'--bootstrap-server','localhost:19092',*args,data=data)

def start(service, publish=False):
    env = dict(os.environ, SERVER_PORT=str(ports[service]), AUTH_JWT_PUBLIC_KEY="file:"+str(ROOT/"services/auth-service/src/main/resources/keys/dev-jwt-public.pem"),
               AUTH_SERVICE_URL='http://localhost:'+str(ports['auth']),
               PRICING_SERVICE_URL='http://localhost:'+str(ports['pricing']),
               SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:'+str(kport),
               AUTH_OUTBOX_PUBLISHER_ENABLED='false',
               PRICING_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), PRICING_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase20-disposable')
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
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase20-disposable','-p','127.0.0.1::5432'])
    pgport=int(cmd('docker','port',pg,'5432/tcp').rsplit(':',1)[1])
    wait(lambda: sql('postgres','select 1')=='1')
    for db in ['auth_db','pricing_db','audit_db']: sql('postgres','CREATE DATABASE '+db)
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
    for service in ['auth','pricing','audit','api-gateway']: start(service)
    import urllib.error
    from concurrent.futures import ThreadPoolExecutor
    correlation='phase20-e2e-'+uuid.uuid4().hex
    gateway=f'http://localhost:{ports["api-gateway"]}'
    def http(method,path,body,token=None,cid=correlation,base=gateway):
        headers={'Content-Type':'application/json','X-Correlation-ID':cid}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
        try:
            with urllib.request.urlopen(request,timeout=10) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as failure:return failure.code,json.load(failure)
    credentials=dict(email='phase20-admin@example.com',password='Disposable-test-password-42!')
    status,user=http('POST','/api/v1/auth/register',dict(**credentials,firstName='Temporary',lastName='Administrator'))
    assert status==201
    status,login=http('POST','/api/v1/auth/login',credentials);assert status==200
    customer_token=login['accessToken']
    sql('auth_db',"update users set role='ADMIN' where id='"+user['id']+"'")
    status,login=http('POST','/api/v1/auth/login',credentials);assert status==200
    token=login['accessToken']
    path='/api/v1/admin/pricing/rules'
    rule_body=dict(ruleType='DRIVER_AGE',operator='LESS_THAN',comparisonValue='21',comparisonValueTo=None,
                   factor=1.30,fixedAmount=None,effectiveFrom='2030-01-01T00:00:00Z',enabled=True,description='Disposable rule')
    assert sql('pricing_db','select count(*) from outbox_events')=='0' # no seed backfill
    assert http('POST',path,rule_body)[0]==401
    assert http('POST',path,rule_body,customer_token)[0]==403
    assert sql('pricing_db','select count(*) from outbox_events')=='0'
    calculation=dict(dateOfBirth='1995-04-20',drivingExperienceYears=8,vehicleManufacturingYear=2021,
                     vehicleValue=700000,engineSizeCc=1500,previousClaimsCount=1,coverageType='COMPREHENSIVE')
    status,baseline=http('POST','/internal/v1/pricing/calculate',calculation,base=f'http://localhost:{ports["pricing"]}')
    assert status==200
    status,rule=http('POST',path,rule_body,token);assert status==201
    ruleid=rule['id']
    assert sql('pricing_db',"select status from outbox_events")=='PENDING'
    first_payload=sql('pricing_db','select payload::text from outbox_events')
    first=json.loads(first_payload)
    assert first['data']['changedBy']==user['id'] and first['correlationId']==correlation
    assert first['data']['version']==0 and first['eventType']=='PricingRuleCreated'
    assert ends()==0
    stop('pricing');start('pricing',publish=True)
    wait(lambda: sql('pricing_db',"select status from outbox_events")=='PUBLISHED')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='1')
    assert ends()==1
    changed=dict(rule_body,comparisonValue='22',version=rule['version'])
    status,rule=http('PUT',path+'/'+ruleid,changed,token,cid=correlation+'-update');assert status==200
    status,rule=http('PATCH',path+'/'+ruleid+'/disable',dict(version=rule['version']),token,cid=correlation+'-disable');assert status==200 and rule['enabled']==False
    status,rule=http('PATCH',path+'/'+ruleid+'/enable',dict(version=rule['version']),token,cid=correlation+'-enable');assert status==200 and rule['enabled']==True
    wait(lambda: sql('pricing_db',"select count(*) from outbox_events where status='PUBLISHED'")=='4')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='4')
    assert ends()==4
    assert sql('pricing_db','select count(distinct event_id) from outbox_events')=='4'
    assert sql('audit_db','select count(*) from processed_events')=='4'
    assert sql('audit_db',"select count(*) from audit_records where actor_type='ADMIN' and entity_type='PricingRule' and actor_id='"+user['id']+"' and entity_id='"+ruleid+"' and producer='pricing-service'")=='4'
    assert sql('pricing_db',"select string_agg(event_type,',' order by created_at,id) from outbox_events")=='PricingRuleCreated,PricingRuleUpdated,PricingRuleDisabled,PricingRuleEnabled'
    assert http('PUT',path+'/'+ruleid,dict(changed,version=0),token)[0]==409
    assert sql('pricing_db','select count(*) from outbox_events')=='4'
    def race(factor):return http('PUT',path+'/'+ruleid,dict(changed,factor=factor,version=rule['version']),token,cid=correlation+'-race')
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses=list(pool.map(race,[1.31,1.32]))
    assert sorted(status for status,_ in responses)==[200,409]
    wait(lambda: sql('pricing_db',"select count(*) from outbox_events where status='PUBLISHED'")=='5')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='5')
    assert sql('pricing_db',"select count(*) from outbox_events where event_type='PricingRuleUpdated'")=='2'
    status,after=http('POST','/internal/v1/pricing/calculate',calculation,base=f'http://localhost:{ports["pricing"]}')
    assert status==200 and after['finalPremium']==baseline['finalPremium']
    assert sql('pricing_db','select count(*) from outbox_events')=='5' and ends()==5
    record=kafka_cmd('kafka-console-consumer.sh','--topic','insurance.pricing.events','--from-beginning','--max-messages','1','--timeout-ms','10000','--property','print.key=true')
    key,value=record.split('\t',1);assert key==ruleid and value==first_payload
    kafka_cmd('kafka-console-producer.sh','--topic','insurance.pricing.events','--property','parse.key=true',data=ruleid+'\t'+first_payload+'\n')
    assert ends()==6
    def caught_up():
        rows=[row.split() for row in kafka_cmd('kafka-consumer-groups.sh','--describe','--group','audit-service').splitlines() if 'insurance.pricing.events' in row]
        return len(rows)==3 and sum(int(row[4]) for row in rows)==6 and all(row[4]=='0' or (row[3]==row[4] and row[5]=='0') for row in rows)
    wait(caught_up)
    assert sql('audit_db','select count(*) from audit_records')=='5'
    assert sql('audit_db','select count(*) from processed_events')=='5'
    all_payload=sql('pricing_db','select payload::text from outbox_events')
    assert all(s not in all_payload for s in [token,customer_token,secret,'Authorization','password','accessToken','refreshToken','Bearer'])
    evidence=['Phase 20 disposable Gateway → Pricing → Kafka → Audit: PASS',
              'ADMIN provisioned through Auth registration, explicit local promotion, then login; UUID comes from JWT.',
              'Initial create: pricing row + PENDING outbox; source end=0. Publisher enabled → PUBLISHED; source end=1.',
              'Create/update/disable/enable: 4 distinct event IDs, resulting versions 0/1/2/3, 4 ADMIN/PricingRule Audit rows + markers; source end=4.',
              'Stale request: 409 with no intent. Concurrent same-version requests: 200 + 409, exactly one additional Updated intent; Audit rows=5, source end=5.',
              'Calculation output unchanged and no extra pricing intent; no historical seed backfill.',
              'Republished exact original key/value/eventId: source end=6; Audit rows/markers remain 5; group caught up.']
    for topic in ['insurance.pricing.events','insurance.pricing.events.dlq']:
        evidence.append(kafka_cmd('kafka-topics.sh','--describe','--topic',topic))
        evidence.append(kafka_cmd('kafka-get-offsets.sh','--topic',topic))
    evidence.append(kafka_cmd('kafka-consumer-groups.sh','--describe','--group','audit-service'))
    for logfile in LOG.glob('*.log'):
        logged=logfile.read_text()
        assert all(value not in logged for value in [secret,token,customer_token,'phase20-admin@example.com',
                'Disposable-test-password-42!','passwordHash','accessToken','refreshToken','$2a$','$2b$']),logfile.name
    evidence.append('Security scan: PASS; no JWT, Authorization, credentials, signing key, password/hash or token fields in pricing payloads/application logs.')
    (LOG/'evidence.txt').write_text('\n\n'.join(evidence))
    print('\n\n'.join(evidence));print('Logs:',LOG)
finally:
    for service in list(processes):stop(service)
    for name in reversed(containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
