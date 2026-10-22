import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { sessionIsActive } from '@app/store/auth/auth.reducer';
import { withGuardSession } from './refresh-guard-session';

export const adminGuard: CanActivateFn = () => {
  const router = inject(Router);
  return withGuardSession((state) => {
    if (!sessionIsActive(state)) {
      return router.createUrlTree(['/login']);
    }
    return state.roles.includes('ADMIN') || router.createUrlTree(['/dashboard']);
  });
};
