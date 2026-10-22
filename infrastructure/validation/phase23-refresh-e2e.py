import subprocess,os,time,json,urllib.request,urllib.error,http.cookiejar,socket,base64,secrets
from pathlib import Path
root=Path(__file__).resolve().parents[2]; procs=[]; cid=None; logs=[]
def port():
 s=socket.socket();s.bind(('127.0.0.1',0));p=s.getsockname()[1];s.close();return p
def req(path,body=None,token=None,cookie=None,opener=None,method=None):
 headers={'Content-Type':'application/json','X-Correlation-ID':'phase23-e2e'}
 if token:headers['Authorization']='Bearer '+token
 if cookie:headers['Cookie']=cookie
 r=urllib.request.Request(base+path,data=json.dumps(body or {}).encode(),headers=headers,method=method or ('POST' if body is not None else 'GET'))
 try:
  with (opener or urllib.request.build_opener()).open(r,timeout=10) as response:return response.status,dict(response.headers),json.loads(response.read() or b'null')
 except urllib.error.HTTPError as e:return e.code,dict(e.headers),json.loads(e.read() or b'null')
try:
 dbport,ap,gp=port(),port(),port();base=f'http://127.0.0.1:{gp}'
 cid=subprocess.check_output(['docker','run','--rm','-d','-p',f'127.0.0.1:{dbport}:5432','-e','POSTGRES_USER=phase23','-e','POSTGRES_PASSWORD=disposable-only','-e','POSTGRES_DB=auth_db','postgres:17.11-bookworm'],text=True).strip()
 for _ in range(40):
  if subprocess.run(['docker','exec',cid,'pg_isready','-U','phase23'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0:break
  time.sleep(.5)
 key=base64.b64encode(secrets.token_bytes(32)).decode()
 env=os.environ.copy();env.update(AUTH_JWT_SECRET=key,AUTH_JWT_ACCESS_TOKEN_TTL='2s',AUTH_REFRESH_TOKEN_TTL='1h',AUTH_REFRESH_COOKIE_SECURE='false',AUTH_OUTBOX_PUBLISHER_ENABLED='false',SPRING_PROFILES_ACTIVE='validation',SPRING_DATASOURCE_URL=f'jdbc:postgresql://localhost:{dbport}/auth_db',SPRING_DATASOURCE_USERNAME='phase23',SPRING_DATASOURCE_PASSWORD='disposable-only',AUTH_SERVICE_URL=f'http://127.0.0.1:{ap}')
 for service,p in [('auth-service',ap),('api-gateway',gp)]:
  f=open('/tmp/phase23-e2e-'+service+'.log','w');logs.append(f)
  procs.append(subprocess.Popen(['java','-jar',str(root/'services'/service/'target'/f'{service}-0.0.1-SNAPSHOT.jar'),f'--server.port={p}'],env=env,stdout=f,stderr=subprocess.STDOUT))
 for _ in range(100):
  try:
   if req('/actuator/health')[0]==200 and urllib.request.urlopen(f'http://127.0.0.1:{ap}/actuator/health').status==200:break
  except Exception:pass
  time.sleep(.5)
 else:raise AssertionError('Services failed to start')
 jar=http.cookiejar.CookieJar();client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
 credentials={'email':'phase23@example.invalid','password':'Disposable-password-42!'}
 status,_,registered=req('/api/v1/auth/register',{**credentials,'firstName':'Phase','lastName':'Test'});assert status==201
 status,h,b=req('/api/v1/auth/login',credentials,opener=client);assert status==200
 raw=next(iter(jar)).value;assert 'HttpOnly' in h['Set-Cookie'] and 'SameSite=Lax' in h['Set-Cookie'] and 'Secure' not in h['Set-Cookie']
 access=b['accessToken'];assert raw not in json.dumps(b)
 assert req('/api/v1/admin/customers',token=access)[0]==403
 time.sleep(3);assert req('/api/v1/admin/customers',token=access)[0]==401
 status,h,b=req('/api/v1/auth/refresh',{},opener=client);assert status==200;assert next(iter(jar)).value!=raw
 assert req('/api/v1/auth/refresh',{},cookie='refresh_token='+raw)[0]==401
 assert req('/api/v1/auth/refresh',{},opener=client)[0]==200
 assert req('/api/v1/auth/logout',{},opener=client)[0]==204;assert not list(jar)
 assert req('/api/v1/auth/refresh',{},opener=client)[0]==401
 assert req('/api/v1/auth/logout',{},opener=client)[0]==204
 admin_credentials={'email':'phase23-admin@example.invalid','password':'Disposable-admin-password-42!'}
 assert req('/api/v1/auth/register',{**admin_credentials,'firstName':'Admin','lastName':'Test'})[0]==201
 subprocess.run(['docker','exec',cid,'psql','-U','phase23','-d','auth_db','-c',"UPDATE users SET role='ADMIN' WHERE email='phase23-admin@example.invalid'"],check=True,stdout=subprocess.DEVNULL)
 admin_jar=http.cookiejar.CookieJar();admin_client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(admin_jar))
 status,_,admin=req('/api/v1/auth/login',admin_credentials,opener=admin_client);assert status==200
 status,_,customer=req('/api/v1/auth/login',credentials,opener=client);assert status==200
 assert req('/api/v1/admin/customers/'+registered['id']+'/disable',{},token=admin['accessToken'],method='PATCH')[0]==200
 # An already-issued access token still authenticates after disable (CUSTOMER lacks ADMIN).
 assert req('/api/v1/admin/customers',token=customer['accessToken'])[0]==403
 assert req('/api/v1/auth/refresh',{},opener=client)[0]==401
 status,_,admin=req('/api/v1/auth/refresh',{},opener=admin_client);assert status==200
 assert req('/api/v1/admin/customers/'+registered['id']+'/enable',{},token=admin['accessToken'],method='PATCH')[0]==200
 assert req('/api/v1/auth/refresh',{},opener=client)[0]==401
 assert req('/api/v1/auth/login',credentials,opener=client)[0]==200
 for f in logs:f.flush()
 for service in ['auth-service','api-gateway']:assert raw not in Path('/tmp/phase23-e2e-'+service+'.log').read_text()
 print('PASS: disposable PostgreSQL + real Gateway/Auth: register, login cookie, protected JWT, expiry 401, rotation, replay 401, repeat rotation, logout, cookie removal, refresh 401, idempotent logout, raw token absent from logs; real ADMIN disable/enable, disabled refresh revoked, re-enable requires login.')
finally:
 for p in procs:
  p.terminate()
 for p in procs:
  try:p.wait(timeout=15)
  except subprocess.TimeoutExpired:p.kill()
 for f in logs:f.close()
 if cid:subprocess.run(['docker','stop',cid],stdout=subprocess.DEVNULL)
