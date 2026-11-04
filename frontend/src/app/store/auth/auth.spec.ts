import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { AuthStore, initialAuthState, sessionIsActive } from './auth.store';
import { session } from '@app/testing/auth-fixtures';

describe('Auth SignalStore', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));
  it('stores only memory session fields and computes roles', () => {
    const store = TestBed.inject(AuthStore);
    expect(store.snapshot()).toEqual(initialAuthState);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const value = session();
    store.loginSuccess(value);
    expect(store.snapshot()).toEqual({ ...value, isAuthenticated: true });
    expect(store.isCustomer()).toBe(true);
    expect(store.isAdmin()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/dashboard');
    store.sessionRestored({ ...session(), roles: ['ADMIN'] });
    expect(store.isAdmin()).toBe(true);
    expect(store.isCustomer()).toBe(false);
    expect(navigate).toHaveBeenCalledTimes(1);
  });
  it('clears all fields on failure, reset and logout; only logout navigates', () => {
    const store = TestBed.inject(AuthStore);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    for (const method of ['loginFailure', 'reset', 'logout'] as const) {
      store.sessionRestored(session());
      store[method]();
      expect(store.snapshot()).toEqual(initialAuthState);
    }
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/login');
  });
  it('treats expired sessions as inactive', () => {
    expect(sessionIsActive(initialAuthState)).toBe(false);
    expect(sessionIsActive({ ...session(), isAuthenticated: true })).toBe(true);
    expect(sessionIsActive({ ...session(), isAuthenticated: true, expiresAt: 1 })).toBe(false);
  });
});
