#!/usr/bin/env python3
"""Disposable Phase 19 validation. Requires packaged jars, Docker and JDK 25.
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
RUN = 'phase19-' + uuid.uuid4().hex[:10]
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
    env = dict(os.environ, SERVER_PORT=str(ports[service]), AUTH_JWT_SECRET=secret,
               AUTH_SERVICE_URL='http://localhost:'+str(ports['auth']),
               SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:'+str(kport),
               AUTH_OUTBOX_PUBLISHER_ENABLED=str(publish).lower(), AUTH_OUTBOX_POLL_INTERVAL='200',
               SPRING_DATASOURCE_USERNAME='test', SPRING_DATASOURCE_PASSWORD='phase19-disposable')
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
    run_container(pg,'postgres:17.11-bookworm',['-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=phase19-disposable','-p','127.0.0.1::5432'])
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
    for topic in ['insurance.user.events','insurance.user.events.dlq','insurance.quote.events','insurance.quote.events.dlq']:
        kafka_cmd('kafka-topics.sh','--create','--topic',topic,'--partitions','3','--replication-factor','1')
    for service in ['auth','notification','audit','api-gateway']: start(service)
    correlation='phase19-e2e-'+uuid.uuid4().hex
    body=json.dumps(dict(email='temporary@example.com',password='Disposable-test-password-42!',firstName='Temporary',lastName='Customer')).encode()
    request=urllib.request.Request(f'http://localhost:{ports["api-gateway"]}/api/v1/auth/register',body,{'Content-Type':'application/json','X-Correlation-ID':correlation})
    with urllib.request.urlopen(request,timeout=10) as response:
        assert response.status==201
        user=json.load(response)['id']
    assert sql('auth_db','select count(*) from users')=='1'
    assert sql('auth_db',"select status from outbox_events")=='PENDING'
    payload=sql('auth_db','select payload::text from outbox_events')
    event=json.loads(payload)
    assert event['correlationId']==correlation and event['aggregateId']==user
    assert event['occurredAt']==event['data']['registeredAt']
    assert set(event['data'])=={'userId','email','role','status','registeredAt'}
    assert all(s not in payload for s in ['password','passwordHash','accessToken','refreshToken','Authorization','$2a$','$2b$'])
    assert ends()==0
    stop('auth');start('auth',publish=True)
    wait(lambda: sql('auth_db','select status from outbox_events')=='PUBLISHED')
    for db,table in [('notification_db','notifications'),('audit_db','audit_records')]:
        wait(lambda: sql(db,'select count(*) from '+table)=='1')
        assert sql(db,'select count(*) from processed_events')=='1'
    assert ends()==1
    assert sql('notification_db',"select type||'/'||status||'/'||(quote_id is null)::text from notifications")=='USER_REGISTERED/CREATED/true'
    assert sql('audit_db',"select event_type||'/'||actor_type||'/'||actor_id||'/'||entity_type||'/'||entity_id||'/'||producer from audit_records")==f'UserRegistered/CUSTOMER/{user}/User/{user}/auth-service'
    assert 'email' not in sql('audit_db','select metadata::text from audit_records')
    record=kafka_cmd('kafka-console-consumer.sh','--topic','insurance.user.events','--from-beginning','--max-messages','1','--timeout-ms','10000','--property','print.key=true')
    key,value=record.split('\t',1)
    assert key==user and value==payload
    kafka_cmd('kafka-console-producer.sh','--topic','insurance.user.events','--property','parse.key=true',data=user+'\t'+payload+'\n')
    assert ends()==2
    def caught_up(group, expected=2):
        rows=kafka_cmd('kafka-consumer-groups.sh','--describe','--group',group).splitlines()
        parts=[row.split() for row in rows if 'insurance.user.events ' in row]
        return (len(parts)==3 and sum(int(row[4]) for row in parts)==expected
                and all(row[4]=='0' or (row[3]==row[4] and row[5]=='0') for row in parts))
    for group in ['notification-service','audit-service']:wait(lambda: caught_up(group))
    for db,table in [('notification_db','notifications'),('audit_db','audit_records')]:
        assert sql(db,'select count(*) from '+table)=='1'
        assert sql(db,'select count(*) from processed_events')=='1'
    evidence=['Phase 19 disposable Gateway → Auth → Kafka → Notification/Audit: PASS',
              'Initial: user=1 outbox=PENDING sourceEnd=0',
              'Published: outbox=PUBLISHED sourceEnd=1 notification=1 audit=1 markers=1 each',
              'Duplicate exact key/value: sourceEnd=2 notification=1 audit=1 markers=1 each',
              'Kafka key/value exactly match userId/stored payload; correlation and safe field allowlist pass.']
    # Only the disposable Notification database rejects this second registration.
    # Audit must still commit independently; Notification exhausts retries to user DLQ.
    sql('notification_db', "alter table notifications add constraint phase19_failure check(type <> 'USER_REGISTERED') NOT VALID")
    body2=json.dumps(dict(email='failure-test@example.com',password='Disposable-test-password-42!',firstName='Temporary',lastName='Customer')).encode()
    request2=urllib.request.Request(f'http://localhost:{ports["api-gateway"]}/api/v1/auth/register',body2,{'Content-Type':'application/json','X-Correlation-ID':correlation+'-failure'})
    with urllib.request.urlopen(request2,timeout=10) as response:
        assert response.status==201
    wait(lambda: sql('audit_db','select count(*) from audit_records')=='2')
    wait(lambda: sql('auth_db',"select count(*) from outbox_events where status='PUBLISHED'")=='2')
    wait(lambda: ends('insurance.user.events.dlq')==1)
    for group in ['notification-service','audit-service']: wait(lambda: caught_up(group,3))
    assert sql('notification_db','select count(*) from notifications')=='1'
    assert sql('notification_db','select count(*) from processed_events')=='1'
    assert sql('audit_db','select count(*) from processed_events')=='2'
    assert ends()==3
    evidence.append('Independent failure: second registration published; Audit rows/markers=2, Notification rows/markers=1; Notification alone recovered to user DLQ.')
    for topic in ['insurance.user.events','insurance.user.events.dlq']:
        evidence.append(kafka_cmd('kafka-topics.sh','--describe','--topic',topic))
        evidence.append(kafka_cmd('kafka-get-offsets.sh','--topic',topic))
    for group in ['notification-service','audit-service']:
        evidence.append(kafka_cmd('kafka-consumer-groups.sh','--describe','--group',group))
    for logfile in LOG.glob('*.log'):
        logged=logfile.read_text()
        assert all(value not in logged for value in [secret,'temporary@example.com','failure-test@example.com',
                'Disposable-test-password-42!','passwordHash','accessToken','refreshToken','$2a$','$2b$']), logfile.name
    evidence.append('Security log scan: PASS; no emails, credentials, hashes, signing key or token fields in application logs.')
    (LOG/'evidence.txt').write_text('\n\n'.join(evidence))
    print('\n\n'.join(evidence))
    print('Logs:',LOG)
finally:
    for service in list(processes): stop(service)
    for name in reversed(containers):
        subprocess.run(['docker','rm','-f','-v',name],capture_output=True)
