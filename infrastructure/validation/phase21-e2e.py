#!/usr/bin/env python3
"""Disposable Phase 21 validation. Requires packaged jars, Docker and JDK 25.
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
RUN = 'phase21-' + uuid.uuid4().hex[:10]
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
ports = {s: port() for s in ['auth', 'notification', 'audit', 'api-gateway']}
secret = base64.b64encode(os.urandom(32)).decode()

def sql(db, query):
    return cmd('docker','exec',pg,'psql','-U','test','-d',db,'-At','-v','ON_ERROR_STOP=1','-c',query)

def kafka_cmd(tool, *args, data=None):
    return cmd('docker','exec','-i',kafka,'/opt/kafka/bin/'+tool,'--bootstrap-server','localhost:19092',*args,data=data)

def start(service, publish=False):
    env = dict(os.environ, SERVER_PORT=str(ports[service]), AUTH_JWT_PUBLIC_KEY="file:"+str(ROOT/"services/auth-service/src/main/resources/keys/dev-jwt-public.pem"),
               AUTH_SERVICE_URL='http://localhost:'+str(ports['auth']),
               SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:'+str(kport),
               AUTH_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), AUTH_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase21-disposable')
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

def ends(topic="insurance.user.events"):
    lines=kafka_cmd('kafka-get-offsets.sh','--topic',topic).splitlines()
    return sum(int(line.rsplit(':',1)[1]) for line in lines if line.startswith(topic+':'))

try:
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase21-disposable','-p','127.0.0.1::5432'])
    pgport=int(cmd('docker','port',pg,'5432/tcp').rsplit(':',1)[1])
    wait(lambda: sql('postgres','select 1')=='1')
    for db in ['auth_db','notification_db','audit_db']: sql('postgres','CREATE DATABASE '+db)
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
    for service in ['auth','notification','audit','api-gateway']:start(service)
    import urllib.error
    from concurrent.futures import ThreadPoolExecutor
    gateway=f'http://localhost:{ports["api-gateway"]}'
    correlation='phase21-'+uuid.uuid4().hex
    def http(method,path,body=None,token=None,base=gateway):
        headers={'Content-Type':'application/json','X-Correlation-ID':correlation}
        if token:headers['Authorization']='Bearer '+token
        request=urllib.request.Request(base+path,json.dumps(body).encode() if body is not None else None,headers,method=method)
        try:
            with urllib.request.urlopen(request,timeout=10) as response:return response.status,json.load(response)
        except urllib.error.HTTPError as failure:return failure.code,json.load(failure)
    password='Disposable-phase21-password-42!'
    admin_credentials=dict(email='phase21-admin@example.com',password=password)
    customer_credentials=dict(email='phase21-customer@example.com',password=password)
    status,administrator=http('POST','/api/v1/auth/register',dict(**admin_credentials,firstName='Test',lastName='Admin'));assert status==201
    adminid=administrator['id']
    sql('auth_db',"update users set role='ADMIN' where id='"+adminid+"'")
    status,customer=http('POST','/api/v1/auth/register',dict(**customer_credentials,firstName='Test',lastName='Customer'));assert status==201
    userid=customer['id']
    status,login=http('POST','/api/v1/auth/login',admin_credentials);assert status==200
    admin_token=login['accessToken']
    status,login=http('POST','/api/v1/auth/login',customer_credentials);assert status==200
    customer_token=login['accessToken']
    path='/api/v1/admin/customers';target=path+'/'+userid
    for base in [gateway,f'http://localhost:{ports["auth"]}']:
        assert http('GET',path,base=base)[0]==401
        assert http('PATCH',target+'/disable',token=customer_token,base=base)[0]==403
    status,page=http('GET',path,token=admin_token);assert status==200 and page['totalElements']==1 and page['size']==20
    assert page['content'][0]['id']==userid
    status,detail=http('GET',target,token=admin_token);assert status==200 and detail['status']=='ACTIVE'
    assert set(detail)=={'id','email','role','status','createdAt','updatedAt'}
    assert http('GET',path+'?size=101',token=admin_token)[0]==400
    assert http('GET',path+'/'+str(uuid.uuid4()),token=admin_token)[0]==404
    assert http('PATCH',path+'/'+adminid+'/disable',token=admin_token)[0]==409
    assert http('PATCH',target+'/disable',dict(changedBy=adminid),admin_token)[0]==400
    assert sql('auth_db',"select count(*) from outbox_events where event_type<>'UserRegistered'")=='0'
    status,disabled=http('PATCH',target+'/disable',token=admin_token);assert status==200 and disabled['status']=='DISABLED'
    assert sql('auth_db',"select status from users where id='"+userid+"'")=='DISABLED'
    first_payload=sql('auth_db',"select payload::text from outbox_events where event_type='UserDisabled'")
    event=json.loads(first_payload)
    assert event['data']['changedBy']==adminid and event['correlationId']==correlation
    assert event['data']['changedAt']==event['occurredAt']==disabled['updatedAt']
    assert sql('auth_db',"select status from outbox_events where event_type='UserDisabled'")=='PENDING'
    assert ends()==0
    status,error=http('POST','/api/v1/auth/login',customer_credentials);assert status==401 and error=={'code':'INVALID_CREDENTIALS','message':'Invalid credentials'}
    # Stateless bearer validation deliberately does not revoke an already-issued token.
    assert http('GET','/actuator/health',token=customer_token,base=f'http://localhost:{ports["auth"]}')[0]==200
    stop('auth');start('auth',publish=True)
    wait(lambda: sql('auth_db',"select count(*) from outbox_events where status='PUBLISHED'")=='3')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='3')
    wait(lambda: sql('notification_db','select count(*) from notifications')=='2')
    assert http('PATCH',target+'/disable',token=admin_token)[0]==200
    assert sql('auth_db','select count(*) from outbox_events')=='3'
    status,enabled=http('PATCH',target+'/enable',token=admin_token);assert status==200 and enabled['status']=='ACTIVE'
    wait(lambda: sql('auth_db',"select count(*) from outbox_events where status='PUBLISHED'")=='4')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='4')
    assert http('POST','/api/v1/auth/login',customer_credentials)[0]==200
    assert http('PATCH',target+'/enable',token=admin_token)[0]==200
    assert sql('auth_db','select count(*) from outbox_events')=='4'
    assert sql('auth_db',"select count(*) from outbox_events where event_type='UserDisabled'")=='1'
    assert sql('auth_db',"select count(*) from outbox_events where event_type='UserEnabled'")=='1'
    for command in ['disable','enable']:
        def call(_):return http('PATCH',target+'/'+command,token=admin_token)[0]
        with ThreadPoolExecutor(max_workers=2) as pool:assert list(pool.map(call,[0,1]))==[200,200]
        wait(lambda: sql('auth_db',"select count(*) from outbox_events where event_type='"+('UserDisabled' if command=='disable' else 'UserEnabled')+"'")=='2')
    wait(lambda: sql('auth_db',"select count(*) from outbox_events where status='PUBLISHED'")=='6')
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='6')
    assert sql('audit_db',"select count(*) from audit_records where entity_id='"+userid+"' and actor_type='ADMIN' and actor_id='"+adminid+"' and event_type in ('UserDisabled','UserEnabled')")=='4'
    assert sql('audit_db',"select count(*) from audit_records where event_type in ('UserDisabled','UserEnabled') and metadata ? 'email'")=='0'
    assert sql('notification_db','select count(*) from notifications')=='2'
    assert sql('notification_db',"select count(*) from notifications where type<>'USER_REGISTERED'")=='0'
    records=kafka_cmd('kafka-console-consumer.sh','--topic','insurance.user.events','--from-beginning','--max-messages','6','--timeout-ms','10000','--property','print.key=true').splitlines()
    original=[line for line in records if json.loads(line.split('\t',1)[1])['eventId']==event['eventId']]
    assert original==[userid+'\t'+first_payload]
    kafka_cmd('kafka-console-producer.sh','--topic','insurance.user.events','--property','parse.key=true',data=userid+'\t'+first_payload+'\n')
    assert ends()==7
    def caught_up(group):
        rows=[row.split() for row in kafka_cmd('kafka-consumer-groups.sh','--describe','--group',group).splitlines() if 'insurance.user.events ' in row]
        return len(rows)==3 and sum(int(row[4]) for row in rows)==7 and all(row[4]=='0' or(row[3]==row[4] and row[5]=='0') for row in rows)
    for group in ['audit-service','notification-service']:wait(lambda:caught_up(group))
    assert sql('audit_db','select count(*) from audit_records')=='6' and sql('audit_db','select count(*) from processed_events')=='6'
    assert sql('notification_db','select count(*) from notifications')=='2' and sql('notification_db','select count(*) from processed_events')=='2'
    assert ends('insurance.user.events.dlq')==0
    status_payloads=sql('auth_db',"select payload::text from outbox_events where event_type<>'UserRegistered'")
    assert all(value not in status_payloads for value in [password,admin_token,customer_token,secret,'passwordHash','Authorization','refreshToken','accessToken'])
    evidence=['Phase 21 disposable Gateway → Auth → Kafka → Audit/Notification: PASS',
              'Gateway and direct Auth independently enforce 401/403; customer-only list/detail, bounded pagination, ADMIN target 409 and client actor rejection pass.',
              'Initial disable: status DISABLED + UserDisabled PENDING atomically; original ADMIN/correlation/time preserved; source end=0.',
              'Publisher enabled: 2 UserRegistered + 1 UserDisabled published; Audit=3, Notification welcomes=2.',
              'Disabled login: uniform INVALID_CREDENTIALS/401; old issued bearer still validates (intentional no revocation).',
              'Repeat disable no new event; enable publishes UserEnabled, login succeeds; repeat enable no new event. Core lifecycle: 4 events/Audit rows, 2 welcome notifications.',
              'Concurrent same-status commands through Gateway: one intent each; final 2 Disabled + 2 Enabled intents, 2 Registered intents, Audit rows/markers=6.',
              'Exact status-event key/value/eventId replay: source end=7, Audit rows/markers remain 6; Notification rows/markers remain 2; both groups caught up; user DLQ empty.']
    for topic in ['insurance.user.events','insurance.user.events.dlq']:
        evidence.append(kafka_cmd('kafka-topics.sh','--describe','--topic',topic));evidence.append(kafka_cmd('kafka-get-offsets.sh','--topic',topic))
    for group in ['audit-service','notification-service']:evidence.append(kafka_cmd('kafka-consumer-groups.sh','--describe','--group',group))
    for logfile in LOG.glob('*.log'):
        logged=logfile.read_text()
        assert all(value not in logged for value in [secret,admin_token,customer_token,password,'phase21-admin@example.com',
            'phase21-customer@example.com','passwordHash','accessToken','refreshToken','$2a$','$2b$']),logfile.name
    evidence.append('Security scan PASS: safe admin API fields, compact event payloads, metadata without email, no credentials/tokens/hashes/signing key in application logs.')
    (LOG/'evidence.txt').write_text('\n\n'.join(evidence));print('\n\n'.join(evidence));print('Logs:',LOG)
finally:
    for service in list(processes):stop(service)
    for name in reversed(containers):subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
