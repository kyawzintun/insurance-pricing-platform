import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature, sessionIsActive } from '@app/store/auth/auth.reducer';
import { AuthActions } from '@app/store/auth/auth.actions';

export const authGuard: CanActivateFn = () => {
  const store = inject(Store);
  const state = store.selectSignal(authFeature.selectAuthState)();
  if (sessionIsActive(state)) return true;
  if (state.isAuthenticated) store.dispatch(AuthActions.loginFailure());
  return inject(Router).createUrlTree(['/login']);
};

export const guestGuard: CanActivateFn = () => {
  const store = inject(Store);
  const state = store.selectSignal(authFeature.selectAuthState)();
  if (sessionIsActive(state)) return inject(Router).createUrlTree(['/dashboard']);
  if (state.isAuthenticated) store.dispatch(AuthActions.loginFailure());
  return true;
};
