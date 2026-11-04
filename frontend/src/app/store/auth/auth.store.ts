import { computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import {
  getState,
  patchState,
  signalStore,
  withComputed,
  withMethods,
  withState,
} from '@ngrx/signals';
import { Role, AuthSession } from '@app/core/auth/auth.models';
import { SessionResetService } from '@app/store/session-reset.service';

export interface AuthState {
  accessToken: string | null;
  userId: string | null;
  roles: Role[];
  expiresAt: number | null;
  isAuthenticated: boolean;
}

export const initialAuthState: AuthState = {
  accessToken: null,
  userId: null,
  roles: [],
  expiresAt: null,
  isAuthenticated: false,
};

export function sessionIsActive(state: AuthState, now = Date.now()): boolean {
  return (
    state.isAuthenticated &&
    !!state.accessToken &&
    !!state.userId &&
    state.expiresAt !== null &&
    state.expiresAt > now
  );
}

export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState(initialAuthState),
  withComputed((store) => ({
    isAdmin: computed(() => store.roles().includes('ADMIN')),
    isCustomer: computed(() => store.roles().includes('CUSTOMER')),
  })),
  withMethods((store, resets = inject(SessionResetService), router = inject(Router)) => ({
    snapshot: () => getState(store),
    sessionRestored(session: AuthSession): void {
      patchState(store, { ...session, roles: [...session.roles], isAuthenticated: true });
    },
    loginSuccess(session: AuthSession): void {
      resets.reset();
      patchState(store, { ...session, roles: [...session.roles], isAuthenticated: true });
      void router.navigateByUrl('/dashboard');
    },
    reset(): void {
      resets.reset();
      patchState(store, { ...initialAuthState, roles: [] });
    },
    loginFailure(): void {
      resets.reset();
      patchState(store, { ...initialAuthState, roles: [] });
    },
    logout(): void {
      resets.reset();
      patchState(store, { ...initialAuthState, roles: [] });
      void router.navigateByUrl('/login');
    },
  })),
);
