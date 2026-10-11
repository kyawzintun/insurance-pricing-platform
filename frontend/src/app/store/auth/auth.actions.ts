import { createActionGroup, emptyProps, props } from '@ngrx/store';
import { AuthSession } from '@app/core/auth/auth.models';

// Passwords are never dispatched. Form credentials go directly to AuthApiService.
export const AuthActions = createActionGroup({
  source: 'Auth', events: {
    'Login Success': props<{ session: AuthSession }>(),
    'Login Failure': emptyProps(),
    'Logout': emptyProps(),
  },
});
