import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { provideMockActions } from '@ngrx/effects/testing';
import { Subject } from 'rxjs';
import { Action } from '@ngrx/store';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature, initialAuthState, sessionIsActive } from '@app/store/auth/auth.reducer';
import { AuthEffects } from '@app/store/auth/auth.effects';
import { session } from '@app/testing/auth-fixtures';

describe('Shared auth state', () => {
  it('starts unauthenticated and stores only session fields on success', () => {
    expect(authFeature.reducer(undefined, { type: 'init' })).toEqual(initialAuthState);
    const value = session();
    const state = authFeature.reducer(
      initialAuthState,
      AuthActions.loginSuccess({ session: value }),
    );
    expect(state).toEqual({ ...value, isAuthenticated: true });
    expect(Object.keys(state).sort()).toEqual([
      'accessToken',
      'expiresAt',
      'isAuthenticated',
      'roles',
      'userId',
    ]);
    expect(authFeature.selectAccessToken({ auth: state })).toBe(value.accessToken);
    expect(authFeature.selectUserId({ auth: state })).toBe(value.userId);
    expect(authFeature.selectRoles({ auth: state })).toEqual(['CUSTOMER']);
    expect(authFeature.selectIsAuthenticated({ auth: state })).toBe(true);
  });
  it('clears all fields on failure and logout', () => {
    const state = { ...session(), isAuthenticated: true };
    expect(authFeature.reducer(state, AuthActions.loginFailure())).toEqual(initialAuthState);
    expect(authFeature.reducer(state, AuthActions.logout())).toEqual(initialAuthState);
  });
  it('treats expired sessions as inactive', () => {
    expect(sessionIsActive(initialAuthState)).toBe(false);
    expect(sessionIsActive({ ...session(), isAuthenticated: true })).toBe(true);
    expect(sessionIsActive({ ...session(), isAuthenticated: true, expiresAt: 1 })).toBe(false);
  });
});

describe('Auth navigation effects', () => {
  it('navigates after password-free success and logout actions', () => {
    const actions = new Subject<Action>();
    const router = { navigateByUrl: vi.fn().mockResolvedValue(true) };
    TestBed.configureTestingModule({
      providers: [
        AuthEffects,
        provideMockActions(() => actions),
        { provide: Router, useValue: router },
      ],
    });
    const effects = TestBed.inject(AuthEffects);
    const subscriptions = [effects.afterLogin.subscribe(), effects.afterLogout.subscribe()];
    actions.next(AuthActions.loginSuccess({ session: session() }));
    expect(router.navigateByUrl).toHaveBeenLastCalledWith('/dashboard');
    actions.next(AuthActions.loginFailure());
    expect(router.navigateByUrl).toHaveBeenCalledTimes(1);
    actions.next(AuthActions.logout());
    expect(router.navigateByUrl).toHaveBeenLastCalledWith('/login');
    subscriptions.forEach((subscription) => subscription.unsubscribe());
  });
});
