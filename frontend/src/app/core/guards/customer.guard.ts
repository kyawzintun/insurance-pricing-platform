import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Store } from '@ngrx/store';
import { authFeature, sessionIsActive } from '@app/store/auth/auth.reducer';
import { AuthActions } from '@app/store/auth/auth.actions';

export const customerGuard: CanActivateFn = () => {
  const store = inject(Store);
  const router = inject(Router);
  const state = store.selectSignal(authFeature.selectAuthState)();
  if (!sessionIsActive(state)) {
    if (state.isAuthenticated) store.dispatch(AuthActions.loginFailure());
    return router.createUrlTree(['/login']);
  }
  return state.roles.includes('CUSTOMER') || router.createUrlTree(['/dashboard']);
};
