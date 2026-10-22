import { SessionService } from '@app/core/auth/session.service';
import { inject } from '@angular/core';
import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Store } from '@ngrx/store';
import { catchError, switchMap, throwError } from 'rxjs';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { authFeature, sessionIsActive } from '@app/store/auth/auth.reducer';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const base = inject(GATEWAY_URL);
  const store = inject(Store);
  const sessions = inject(SessionService);
  const readAuth = store.selectSignal(authFeature.selectAuthState);
  // Match the configured origin AND API path boundary. Never send our token to another origin.
  const gateway = new URL(base);
  const target = new URL(request.url, document.baseURI);
  const apiPath = gateway.pathname.replace(/\/$/, '') + '/api/';

  if (target.origin !== gateway.origin || !target.pathname.startsWith(apiPath)) {
    return next(request);
  }

  const authPath = target.pathname.slice(apiPath.length);
  if (
    request.method === 'POST' &&
    ['v1/auth/login', 'v1/auth/register', 'v1/auth/refresh', 'v1/auth/logout'].includes(authPath)
  ) {
    return next(request);
  }

  const state = readAuth();
  const token = sessionIsActive(state) ? state.accessToken : null;

  const authorized = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;

  return next(authorized).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse && error.status === 401 && state.isAuthenticated) {
        const current = readAuth();
        if (!current.isAuthenticated) return throwError(() => error);
        if (
          current.accessToken &&
          current.accessToken !== state.accessToken &&
          sessionIsActive(current)
        ) {
          return next(
            request.clone({ setHeaders: { Authorization: `Bearer ${current.accessToken}` } }),
          );
        }
        return sessions.refresh().pipe(
          switchMap((session) =>
            next(
              request.clone({
                setHeaders: { Authorization: `Bearer ${session.accessToken}` },
              }),
            ),
          ),
        );
      }
      return throwError(() => error);
    }),
  );
};
