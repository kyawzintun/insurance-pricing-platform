import { inject, Injectable } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { createAction, createFeature, createReducer, on, props } from '@ngrx/store';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, takeUntil } from 'rxjs';
import {
  OperationsApiService,
  OperationsOverview,
} from '@app/core/operations/operations-api.service';
import { AuthActions } from '@app/store/auth/auth.actions';

export const OperationsActions = {
  refresh: createAction('[Operations] Refresh'),
  loaded: createAction('[Operations] Loaded', props<{ overview: OperationsOverview }>()),
  failed: createAction('[Operations] Failed', props<{ message: string }>()),
  leave: createAction('[Operations] Leave'),
};
export const initialOperationsState = {
  overview: null as OperationsOverview | null,
  loading: false,
  error: null as string | null,
};
export const operationsFeature = createFeature({
  name: 'operations',
  reducer: createReducer(
    initialOperationsState,
    on(OperationsActions.refresh, (s) => ({ ...s, loading: true, error: null })),
    on(OperationsActions.loaded, (s, { overview }) => ({
      ...s,
      overview,
      loading: false,
      error: null,
    })),
    on(OperationsActions.failed, (s, { message }) => ({ ...s, loading: false, error: message })),
    on(OperationsActions.leave, (s) => ({ ...s, loading: false })),
    on(
      AuthActions.logout,
      AuthActions.loginFailure,
      AuthActions.loginSuccess,
      () => initialOperationsState,
    ),
  ),
});
@Injectable()
export class OperationsEffects {
  private readonly actions = inject(Actions);
  private readonly api = inject(OperationsApiService);
  readonly refresh = createEffect(() =>
    this.actions.pipe(
      ofType(OperationsActions.refresh),
      exhaustMap(() =>
        this.api.overview().pipe(
          map((overview) => OperationsActions.loaded({ overview })),
          catchError((error: unknown) =>
            of(
              OperationsActions.failed({
                message:
                  error instanceof HttpErrorResponse && error.status === 403
                    ? 'Access denied. Operations requires ADMIN access.'
                    : 'Operations is unavailable. Please try again.',
              }),
            ),
          ),
          takeUntil(
            this.actions.pipe(
              ofType(
                OperationsActions.leave,
                AuthActions.logout,
                AuthActions.loginFailure,
                AuthActions.loginSuccess,
              ),
            ),
          ),
        ),
      ),
    ),
  );
}
