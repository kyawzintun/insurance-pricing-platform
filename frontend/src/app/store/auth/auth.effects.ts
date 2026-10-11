import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { tap } from 'rxjs';
import { AuthActions } from '@app/store/auth/auth.actions';

@Injectable()
export class AuthEffects {
  private readonly actions = inject(Actions);
  private readonly router = inject(Router);
  readonly afterLogin = createEffect(
    () =>
      this.actions.pipe(
        ofType(AuthActions.loginSuccess),
        tap(() => {
          void this.router.navigateByUrl('/dashboard');
        }),
      ),
    { dispatch: false },
  );
  readonly afterLogout = createEffect(
    () =>
      this.actions.pipe(
        ofType(AuthActions.logout),
        tap(() => {
          void this.router.navigateByUrl('/login');
        }),
      ),
    { dispatch: false },
  );
}
