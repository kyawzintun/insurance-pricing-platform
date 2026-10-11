import { inject } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { catchError, throwError } from 'rxjs';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature, sessionIsActive } from '@app/store/auth/auth.reducer';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const base = inject(GATEWAY_URL);
  const store = inject(Store);
  const readAuth = store.selectSignal(authFeature.selectAuthState);
  // Match the configured origin AND API path boundary. Never send our token to another origin.
  const gateway = new URL(base);
  const target = new URL(request.url, document.baseURI);
  const apiPath = gateway.pathname.replace(/\/$/, '') + '/api/';

  if (target.origin !== gateway.origin || !target.pathname.startsWith(apiPath)) {
    return next(request);
  }

  const authPath = target.pathname.slice(apiPath.length);
  if (request.method === 'POST' && ['v1/auth/login', 'v1/auth/register'].includes(authPath)) {
    return next(request);
  }

  const state = readAuth();
  const token = sessionIsActive(state) ? state.accessToken : null;
  if (state.isAuthenticated && !token) store.dispatch(AuthActions.logout());

  const authorized = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      // A late 401 from an old request must not log out a newly established session.
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        token &&
        readAuth().accessToken === token
      ) {
        store.dispatch(AuthActions.logout());
      }
      return throwError(() => error);
    }),
  );
};
