import { Router } from '@angular/router';
import { AuthStore } from '@app/store/auth/auth.store';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { SessionService } from './session.service';
import { GATEWAY_URL } from './gateway.config';
import { session, token, USER_ID } from '@app/testing/auth-fixtures';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';
describe('Cookie session lifecycle', () => {
  let backend: HttpTestingController;
  let service: SessionService;
  let store: InstanceType<typeof AuthStore>;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://localhost:8080' },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    service = TestBed.inject(SessionService);
    store = TestBed.inject(AuthStore);
  });
  afterEach(() => backend.verify());
  it.each(['CUSTOMER', 'ADMIN'])('restores %s state without login navigation', (role) => {
    service.restore().subscribe();
    const request = backend.expectOne('http://localhost:8080/api/v1/auth/refresh');
    expect(request.request.withCredentials).toBe(true);
    request.flush({
      accessToken: token({ sub: USER_ID, roles: [role], exp: Math.floor(Date.now() / 1000) + 900 }),
    });
    expect(TestBed.inject(AuthStore).roles()).toEqual([role]);
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(true);
  });
  it('leaves startup signed out after one failed refresh', () => {
    let result: unknown;
    service.restore().subscribe((value) => (result = value));
    backend
      .expectOne('http://localhost:8080/api/v1/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(result).toBeNull();
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(false);
  });
  it('clears local state even when logout fails', () => {
    TestBed.inject(AuthStore).loginSuccess(session());
    service.logout();
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(false);
    const request = backend.expectOne('http://localhost:8080/api/v1/auth/logout');
    expect(request.request.withCredentials).toBe(true);
    request.error(new ProgressEvent('error'));
    expect(TestBed.inject(AuthStore).accessToken()).toBeNull();
  });
  it('does not restore a refresh response arriving after logout', () => {
    service.restore().subscribe();
    service.logout();
    backend.expectNone('http://localhost:8080/api/v1/auth/logout');
    backend.expectOne('http://localhost:8080/api/v1/auth/refresh').flush({ accessToken: token() });
    backend.expectOne('http://localhost:8080/api/v1/auth/logout').flush(null);
    expect(TestBed.inject(AuthStore).isAuthenticated()).toBe(false);
  });
  beforeEach(() => {
    const router = TestBed.inject(Router, null);
    if (router) {
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      vi.spyOn(router, 'navigate').mockResolvedValue(true);
    }
  });
});
