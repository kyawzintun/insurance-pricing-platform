import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { sessionIsActive } from '@app/store/auth/auth.reducer';
import { withGuardSession } from './refresh-guard-session';

export const authGuard: CanActivateFn = () => {
  const router = inject(Router);
  return withGuardSession((state) => sessionIsActive(state) || router.createUrlTree(['/login']));
};

export const guestGuard: CanActivateFn = () => {
  const router = inject(Router);
  return withGuardSession((state) =>
    sessionIsActive(state) ? router.createUrlTree(['/dashboard']) : true,
  );
};
