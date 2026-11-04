import { inject } from '@angular/core';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, Subject, takeUntil, tap } from 'rxjs';
import { SessionResetService } from '@app/store/session-reset.service';
import { HttpErrorResponse } from '@angular/common/http';
import {
  OperationsApiService,
  OperationsOverview,
} from '@app/core/operations/operations-api.service';

export const initialOperationsState = {
  overview: null as OperationsOverview | null,
  loading: false,
  error: null as string | null,
};

export const OperationsStore = signalStore(
  { providedIn: 'root' },
  withState(initialOperationsState),
  withMethods((store, api = inject(OperationsApiService), resets = inject(SessionResetService)) => {
    const cancelled = new Subject<void>();
    const reset = () => {
      cancelled.next();
      patchState(store, initialOperationsState);
    };
    const unregister = resets.register(reset);
    return {
      reset,
      _dispose: () => {
        unregister();
        cancelled.next();
        cancelled.complete();
      },
      leave(): void {
        cancelled.next();
        patchState(store, { loading: false });
      },
      refresh: rxMethod<void>(
        pipe(
          exhaustMap(() => {
            patchState(store, { loading: true, error: null });
            return api.overview().pipe(
              tap((overview) => patchState(store, { overview, loading: false, error: null })),
              catchError((error) => {
                patchState(store, {
                  loading: false,
                  error:
                    error instanceof HttpErrorResponse && error.status === 403
                      ? 'Access denied. Operations requires ADMIN access.'
                      : 'Operations is unavailable. Please try again.',
                });
                return EMPTY;
              }),
              takeUntil(cancelled),
            );
          }),
        ),
      ),
    };
  }),
  withHooks({ onDestroy: (store) => store._dispose() }),
);
