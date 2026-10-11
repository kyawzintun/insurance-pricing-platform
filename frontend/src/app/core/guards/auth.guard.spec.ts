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
import { session } from '@app/testing/auth-fixtures';

describe('Auth guards', () => {
  beforeEach(() =>
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideStore(), provideState(authFeature)],
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
  it('clears expired state and redirects to login', () => {
    const store = TestBed.inject(Store);
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), expiresAt: 1 } }));
    expect(TestBed.inject(Router).serializeUrl(run(authGuard) as UrlTree)).toBe('/login');
    expect(store.selectSignal(authFeature.selectIsAuthenticated)()).toBe(false);
  });
});
