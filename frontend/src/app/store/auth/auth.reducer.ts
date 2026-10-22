import { createFeature, createReducer, on } from '@ngrx/store';
import { AuthActions } from '@app/store/auth/auth.actions';
import { Role } from '@app/core/auth/auth.models';

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

export const authFeature = createFeature({
  name: 'auth',
  reducer: createReducer(
    initialAuthState,
    on(AuthActions.loginSuccess, AuthActions.sessionRestored, (_state, { session }) => ({
      ...session,
      roles: [...session.roles],
      isAuthenticated: true,
    })),
    on(AuthActions.loginFailure, AuthActions.logout, () => ({ ...initialAuthState, roles: [] })),
  ),
});

export function sessionIsActive(state: AuthState, now = Date.now()): boolean {
  return (
    state.isAuthenticated &&
    !!state.accessToken &&
    !!state.userId &&
    state.expiresAt !== null &&
    state.expiresAt > now
  );
}
