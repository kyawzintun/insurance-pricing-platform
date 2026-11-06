#!/usr/bin/env python3
"""Disposable PostgreSQL plan regression. Docker only; never connects to development DBs.
Applies immutable service SQL migrations in version order (Flyway itself is checked by postgres-it).
Writes full JSON plans, SQL, results and index sizes to --output. No timing assertions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--output', default='/tmp/phase27-performance.json')
args = parser.parse_args()
name = 'phase27-perf-' + uuid.uuid4().hex[:10]

def cmd(*parts, data=None):
    result = subprocess.run(parts, input=data, text=True, capture_output=True)
    if result.returncode: raise RuntimeError(f'{parts}: {result.stderr}')
    return result.stdout.strip()

def sql(db, query):
    return cmd('docker', 'exec', '-i', name, 'psql', '-h', '127.0.0.1', '-X', '-U', 'test', '-d', db,
               '-At', '-v', 'ON_ERROR_STOP=1', data=query)

def migrations(service):
    return sorted((ROOT / f'services/{service}-service/src/main/resources/db/migration').glob('V*.sql'),
                  key=lambda p: int(p.name.split('__')[0][1:]))

queries = {
'audit': {
 'count': 'SELECT count(*) FROM audit_records',
 'latest': 'SELECT * FROM audit_records ORDER BY occurred_at DESC,id DESC LIMIT 20',
 'correlation': "SELECT * FROM audit_records WHERE correlation_id='corr-4242' ORDER BY occurred_at DESC,id DESC LIMIT 20",
 'entity': "SELECT * FROM audit_records WHERE entity_type='Quote' AND entity_id=md5('q42')::uuid::text ORDER BY occurred_at DESC,id DESC LIMIT 20",
 'event': "SELECT * FROM audit_records WHERE event_type='QuotePriced' ORDER BY occurred_at DESC,id DESC LIMIT 20"},
'quote': {
 'count': 'SELECT count(*) FROM quotes',
 'customer_count': "SELECT count(*) FROM quotes WHERE customer_id=md5('customer42')::uuid",
 'customer': "SELECT id FROM quotes WHERE customer_id=md5('customer42')::uuid ORDER BY created_at DESC,id DESC LIMIT 20",
 'admin': 'SELECT id FROM quotes ORDER BY created_at DESC,id DESC LIMIT 20',
 'deep_admin': 'SELECT id FROM quotes ORDER BY created_at DESC,id DESC OFFSET 40000 LIMIT 20',
 'expiration': "SELECT id,quote_reference,customer_id,coverage_type,premium_amount,currency,expires_at FROM quotes WHERE status='PRICED' AND expires_at <= '2026-11-04' ORDER BY expires_at,id LIMIT 100 FOR UPDATE SKIP LOCKED"},
'pricing': {
 'active': "SELECT * FROM pricing_rules WHERE enabled AND effective_from <= '2026-11-04'",
 'admin': "SELECT * FROM pricing_rules WHERE rule_type='BASE_PREMIUM' AND enabled ORDER BY id LIMIT 20"},
'auth': {
 'customer_count': "SELECT count(*) FROM users WHERE role='CUSTOMER'",
 'email': "SELECT * FROM users WHERE upper(email)=upper('customer4242@example.test')",
 'customers': "SELECT * FROM users WHERE role='CUSTOMER' ORDER BY created_at DESC,id DESC LIMIT 20"}}
for service in ['auth', 'quote', 'pricing']:
    queries[service]['outbox'] = "SELECT id,event_id,aggregate_id,event_type,payload::text,correlation_id,retry_count FROM outbox_events WHERE status='PENDING' ORDER BY created_at,id LIMIT 100"

fixtures = {
'audit': """INSERT INTO audit_records SELECT md5('a'||g)::uuid,md5('e'||g)::uuid,
 CASE WHEN g%5=0 THEN 'UserRegistered' ELSE 'QuotePriced' END,'CUSTOMER',md5('customer'||(g%1000))::uuid::text,
 'Quote',md5('q'||(g%1000))::uuid::text,'corr-'||(g/3), '2026-01-01'::timestamptz + (g/10)*interval '1 second',
 'quote-service','{"status":"PRICED"}'::jsonb,'2026-01-01' FROM generate_series(1,100000) g;""",
'quote': """INSERT INTO quotes SELECT md5('q'||g)::uuid,'Q-'||g,md5('customer'||(g%1000))::uuid,
 CASE WHEN g%10=0 THEN 'EXPIRED' ELSE 'PRICED' END,'COMPREHENSIVE',8000,'THB',
 '2026-01-01'::timestamptz+(g/10)*interval '1 second','2026-01-01',
 '2026-11-01'::timestamptz+g*interval '1 minute','2026-01-01' FROM generate_series(1,50000) g;""",
'pricing': """INSERT INTO pricing_rules SELECT md5('r'||g)::uuid,'BASE_PREMIUM','EQUALS',NULL,NULL,NULL,8000,
 '2025-01-01'::timestamptz+g*interval '1 hour',g%4<>0,'historical rule',0,'2025-01-01','2025-01-01'
 FROM generate_series(1,5000) g;""",
'auth': """INSERT INTO users SELECT md5('u'||g)::uuid,'customer'||g||'@example.test',repeat('x',60),'Test','Customer',
 CASE WHEN g%100=0 THEN 'ADMIN' ELSE 'CUSTOMER' END,'ACTIVE',
 '2026-01-01'::timestamptz+(g/10)*interval '1 second','2026-01-01' FROM generate_series(1,15000) g;"""}
outbox = """INSERT INTO outbox_events SELECT md5('o'||g)::uuid,md5('oe'||g)::uuid,'Quote',md5('q'||g)::text,
 'QuotePriced',1,jsonb_build_object('data',repeat('x',300)),'corr-'||g,
 CASE WHEN g>99900 THEN 'PENDING' ELSE 'PUBLISHED' END,0,
 '2026-01-01'::timestamptz+(g/10)*interval '1 second',NULL FROM generate_series(1,100000) g;"""
report = {'postgres': 'postgres:17.11-bookworm', 'datasets': {'audit':100000,'quote':50000,'pricing':5007,'auth':15000,'each_outbox':100000,'pending_each':100}, 'queries': {}}
# Explicit boundary keeps future migrations out of baseline and includes all changes in after.
boundary = {'audit':3,'quote':6,'pricing':4,'auth':4}

def capture(service, query):
    plan = json.loads(sql(service, 'EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) '+query))[0]
    rows = sql(service, 'SELECT coalesce(json_agg(t),\'[]\'::json) FROM ('+query+') t;')
    return {'plan':plan, 'rows':json.loads(rows)}

try:
    cmd('docker','run','-d','--name',name,'-e','POSTGRES_USER=test','-e','POSTGRES_PASSWORD=disposable','postgres:17.11-bookworm')
    for _ in range(100):
        try:
            cmd('docker','exec',name,'pg_isready','-h','127.0.0.1','-U','test'); break
        except RuntimeError: time.sleep(.2)
    else: raise RuntimeError('PostgreSQL not ready')
    for service in queries:
        sql('postgres',f'CREATE DATABASE {service};')
        for migration in migrations(service):
            if int(migration.name.split('__')[0][1:]) <= boundary[service]: sql(service,migration.read_text())
        sql(service,fixtures[service] + (outbox if service != 'audit' else '') + ' ANALYZE;')
        for key, query in queries[service].items():
            report['queries'][service+'.'+key] = {'sql':query,'before':capture(service,query)}
    # Save baseline before any optimization is applied.
    Path(args.output).write_text(json.dumps(report,indent=2)+'\n')
    for service in queries:
        for migration in migrations(service):
            if int(migration.name.split('__')[0][1:]) > boundary[service]: sql(service,migration.read_text())
        sql(service,'ANALYZE;')
        for key, query in queries[service].items():
            item = report['queries'][service+'.'+key]
            item['after'] = capture(service,query)
            if key != 'active': assert item['before']['rows'] == item['after']['rows'], service+'.'+key
            else: assert sorted(str(r) for r in item['before']['rows']) == sorted(str(r) for r in item['after']['rows'])
        report[service+'_indexes'] = sql(service,"SELECT indexrelname,pg_relation_size(indexrelid) FROM pg_stat_user_indexes ORDER BY indexrelname")
    expected = {'audit.correlation':'idx_audit_records_correlation_id', 'quote.admin':'idx_quotes_created_id',
                'auth.email':'idx_users_upper_email', 'auth.customers':'idx_users_role_created_id'}
    for key, index in expected.items():
        plan = json.dumps(report['queries'][key]['after']['plan'])
        assert index in plan and 'Seq Scan' not in plan, key
    # Equality above checks every selected field in order, not only a checksum. Keep evidence compact.
    for item in report['queries'].values():
        for stage in ['before', 'after']:
            rows = item[stage].pop('rows')
            item[stage]['row_count'] = len(rows)
            item[stage]['result_sha256'] = hashlib.sha256(json.dumps(rows,sort_keys=True).encode()).hexdigest()
    Path(args.output).write_text(json.dumps(report,indent=2)+'\n')
    for key,item in report['queries'].items():
        print(key, *(f'{stage}={item[stage]["plan"]["Execution Time"]}ms' for stage in ['before','after']))
    print('PASS: identical results; full evidence:',args.output)
finally:
    cmd('docker','rm','-f',name)
