import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideState, provideStore, Store } from '@ngrx/store';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature } from '@app/store/auth/auth.reducer';
import { session } from '@app/testing/auth-fixtures';

describe('Gateway auth interceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let store: Store;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideStore(),
        provideState(authFeature),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://localhost:8080' },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    store = TestBed.inject(Store);
  });
  afterEach(() => backend.verify());
  const url = 'http://localhost:8080/api/v1/protected-test';
  it('adds the current memory token to Gateway API calls', () => {
    const value = session();
    store.dispatch(AuthActions.loginSuccess({ session: value }));
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
  it.each(['login', 'register'])('excludes POST %s and does not redirect on its 401', (path) => {
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    http.post(`http://localhost:8080/api/v1/auth/${path}`, {}).subscribe({ error: () => {} });
    const request = backend.expectOne(`http://localhost:8080/api/v1/auth/${path}`);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(true);
  });
  it.each([
    'https://example.invalid/api/v1/test',
    'http://localhost.evil.invalid:8080/api/test',
    'http://localhost:8080/api-other/test',
    'http://localhost:8080/actuator/health',
  ])('never leaks the token outside the configured API: %s', (target) => {
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    http.get(target).subscribe();
    const request = backend.expectOne(target);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });
  it('clears the matching session on protected 401 without retry or refresh', () => {
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    const dispatch = vi.spyOn(store, 'dispatch');
    http.get(url).subscribe({ error: () => {} });
    backend.expectOne(url).flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(dispatch).toHaveBeenCalledWith(AuthActions.logout());
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
    backend.expectNone(url);
  });
  it('does not clear a newer session for an older request failure', () => {
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    http.get(url).subscribe({ error: () => {} });
    const request = backend.expectOne(url);
    store.dispatch(
      AuthActions.loginSuccess({ session: { ...session(), accessToken: 'newer-token' } }),
    );
    request.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(store.selectSignal(authFeature.selectAccessToken)()).toBe('newer-token');
  });
  it('clears an expired token without attaching it', () => {
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), expiresAt: 1 } }));
    http.get(url).subscribe();
    const request = backend.expectOne(url);
    expect(request.request.headers.has('Authorization')).toBe(false);
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
    request.flush({});
  });
  it('does not log out on 403', () => {
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    http.get(url).subscribe({ error: () => {} });
    backend.expectOne(url).flush({}, { status: 403, statusText: 'Forbidden' });
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(true);
  });
});
