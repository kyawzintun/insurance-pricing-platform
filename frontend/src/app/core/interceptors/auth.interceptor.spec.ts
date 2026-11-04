import { Router } from '@angular/router';
import { AuthStore } from '@app/store/auth/auth.store';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { token } from '@app/testing/auth-fixtures';
import { session } from '@app/testing/auth-fixtures';

describe('Gateway auth interceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let store: InstanceType<typeof AuthStore>;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://localhost:8080' },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    store = TestBed.inject(AuthStore);
  });
  afterEach(() => backend.verify());
  const url = 'http://localhost:8080/api/v1/protected-test';
  it('adds the current memory token to Gateway API calls', () => {
    const value = session();
    TestBed.inject(AuthStore).loginSuccess(value);
    http.get(url).subscribe();
    const request = backend.expectOne(url);
    expect(request.request.headers.get('Authorization')).toBe(`Bearer ${value.accessToken}`);
    request.flush({});
  });
  it('does not attach a token when signed out', () => {
    http.get(url).subscribe();
    const request = backend.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });
  it.each(['login', 'register', 'refresh', 'logout'])(
    'excludes POST %s and does not redirect on its 401',
    (path) => {
      TestBed.inject(AuthStore).loginSuccess(session());
      http.post(`http://localhost:8080/api/v1/auth/${path}`, {}).subscribe({ error: () => {} });
      const request = backend.expectOne(`http://localhost:8080/api/v1/auth/${path}`);
      expect(request.request.headers.has('Authorization')).toBe(false);
      request.flush({}, { status: 401, statusText: 'Unauthorized' });
      expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(true);
    },
  );
  it.each([
    'https://example.invalid/api/v1/test',
    'http://localhost.evil.invalid:8080/api/test',
    'http://localhost:8080/api-other/test',
    'http://localhost:8080/actuator/health',
  ])('never leaks the token outside the configured API: %s', (target) => {
    TestBed.inject(AuthStore).loginSuccess(session());
    http.get(target).subscribe();
    const request = backend.expectOne(target);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });
  it('clears the matching session when refresh fails', () => {
    TestBed.inject(AuthStore).loginSuccess(session());
    const clear = vi.spyOn(store, 'loginFailure');
    http.get(url).subscribe({ error: () => {} });
    backend.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
    backend
      .expectOne('http://localhost:8080/api/v1/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(clear).toHaveBeenCalledWith();
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(false);
    backend.expectNone(url);
  });
  it('does not clear a newer session for an older request failure', () => {
    TestBed.inject(AuthStore).loginSuccess(session());
    http.get(url).subscribe({ error: () => {} });
    const request = backend.expectOne(url);
    TestBed.inject(AuthStore).loginSuccess({ ...session(), accessToken: 'newer-token' });
    request.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(TestBed.inject(AuthStore).accessToken()).toBe('newer-token');
    const retry = backend.expectOne(url);
    expect(retry.request.headers.get('Authorization')).toBe('Bearer newer-token');
    retry.flush({});
  });
  it('keeps refreshable expired state without attaching the expired token', () => {
    TestBed.inject(AuthStore).loginSuccess({ ...session(), expiresAt: 1 });
    http.get(url).subscribe();
    const request = backend.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(true);
    request.flush({});
  });
  it('queues three 401s behind one refresh and retries each request', () => {
    TestBed.inject(AuthStore).loginSuccess(session());
    let completed = 0;
    for (let i = 0; i < 3; i++) http.get(url + '?i=' + i).subscribe(() => completed++);
    backend
      .match((r) => r.url.startsWith(url))
      .forEach((r) => r.flush({}, { status: 401, statusText: 'Unauthorized' }));
    const refresh = backend.expectOne('http://localhost:8080/api/v1/auth/refresh');
    expect(refresh.request.withCredentials).toBe(true);
    const accessToken = token({
      sub: session().userId,
      roles: ['ADMIN'],
      exp: Math.floor(Date.now() / 1000) + 900,
    });
    refresh.flush({ accessToken, tokenType: 'Bearer', expiresIn: 900 });
    const retries = backend.match((r) => r.url.startsWith(url));
    expect(retries.length).toBe(3);
    retries.forEach((r) => {
      expect(r.request.headers.get('Authorization')).toBe(`Bearer ${accessToken}`);
      r.flush({});
    });
    expect(completed).toBe(3);
    expect(TestBed.inject(AuthStore).roles()).toEqual(['ADMIN']);
  });
  it('does not log out on 403', () => {
    TestBed.inject(AuthStore).loginSuccess(session());
    http.get(url).subscribe({ error: () => {} });
    backend.expectOne(url).flush({}, { status: 403, statusText: 'Forbidden' });
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(true);
  });
  beforeEach(() => {
    const router = TestBed.inject(Router, null);
    if (router) {
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      vi.spyOn(router, 'navigate').mockResolvedValue(true);
    }
  });
});
