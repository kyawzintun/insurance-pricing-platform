import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { firstValueFrom, Observable } from 'rxjs';
import { TestBed } from '@angular/core/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { provideState, provideStore, Store } from '@ngrx/store';
import { authGuard, guestGuard } from '@app/core/guards/auth.guard';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature } from '@app/store/auth/auth.reducer';
import { token } from '@app/testing/auth-fixtures';
import { session } from '@app/testing/auth-fixtures';

describe('Auth guards', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideStore(), provideState(authFeature), provideHttpClient(), provideHttpClientTesting()],
    }),
  );
  function run(guard: typeof authGuard) {
    return TestBed.runInInjectionContext(() =>
      guard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
    );
  }
  it('redirects a signed-out dashboard request to login', () => {
    expect(TestBed.inject(Router).serializeUrl(run(authGuard) as UrlTree)).toBe('/login');
    expect(run(guestGuard)).toBe(true);
  });
  it('allows an active session and redirects guest pages to dashboard', () => {
    TestBed.inject(Store).dispatch(AuthActions.loginSuccess({ session: session() }));
    expect(run(authGuard)).toBe(true);
    expect(TestBed.inject(Router).serializeUrl(run(guestGuard) as UrlTree)).toBe('/dashboard');
  });
  it('waits for successful renewal before allowing expired-session navigation', async () => {
    const store=TestBed.inject(Store);
    store.dispatch(AuthActions.loginSuccess({session:{...session(),expiresAt:1}}));
    const decision=firstValueFrom(run(authGuard) as Observable<boolean>);
    TestBed.inject(HttpTestingController).expectOne(r=>r.url.endsWith('/auth/refresh')).flush({accessToken:token()});
    expect(await decision).toBe(true);
  });
  it('waits for refresh before redirecting an expired session', async () => {
    const store = TestBed.inject(Store);
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), expiresAt: 1 } }));
    const result=firstValueFrom(run(authGuard) as Observable<UrlTree>);
    TestBed.inject(HttpTestingController).expectOne(r=>r.url.endsWith('/auth/refresh')).flush({}, {status:401,statusText:'Unauthorized'});
    expect(TestBed.inject(Router).serializeUrl(await result)).toBe('/login');
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
});
