import { inject } from '@angular/core';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, pipe, Subject, switchMap, takeUntil, tap } from 'rxjs';
import { SessionResetService } from '@app/store/session-reset.service';
import { HttpErrorResponse } from '@angular/common/http';
import { AuditDetail, AuditPage, AuditQuery } from '@app/core/audit/audit.models';
import { AuditApiService } from '@app/core/audit/audit-api.service';
interface AuditState {
  query: AuditQuery;
  result: AuditPage;
  detail: AuditDetail | null;
  listLoading: boolean;
  detailLoading: boolean;
  listError: string | null;
  detailError: string | null;
}

export const initialAuditState: AuditState = {
  query: {
    page: 0,
    size: 20,
  },
  result: {
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  },
  detail: null,
  listLoading: false,
  detailLoading: false,
  listError: null,
  detailError: null,
};

export function auditError(error: unknown): string {
  const status = error instanceof HttpErrorResponse ? error.status : 0;
  if (status === 403) return 'Access denied. Audit history requires ADMIN access.';
  if (status === 404) return 'Audit record not found.';
  if (status === 400) return 'Invalid audit filters. Check IDs, UTC timestamps and date range.';
  return 'Audit history is unavailable. Please try again.';
}

export const AuditStore = signalStore(
  { providedIn: 'root' },
  withState(initialAuditState),
  withMethods((store, api = inject(AuditApiService), resets = inject(SessionResetService)) => {
    const cancelled = new Subject<void>();
    const reset = () => {
      cancelled.next();
      patchState(store, initialAuditState);
    };
    const unregister = resets.register(reset);
    return {
      reset,
      _dispose: () => {
        unregister();
        cancelled.next();
        cancelled.complete();
      },
      loadList: rxMethod<{ query: AuditQuery }>(
        pipe(
          switchMap(({ query }) => {
            patchState(store, {
              query,
              result: { ...initialAuditState.result, page: query.page, size: query.size },
              listLoading: true,
              listError: null,
            });
            return api.list(query).pipe(
              tap((result) => patchState(store, { result, listLoading: false })),
              catchError((error) => {
                patchState(store, { listError: auditError(error), listLoading: false });
                return EMPTY;
              }),
              takeUntil(cancelled),
            );
          }),
        ),
      ),
      loadDetail: rxMethod<{ id: string }>(
        pipe(
          switchMap(({ id }) => {
            patchState(store, { detail: null, detailLoading: true, detailError: null });
            return api.detail(id).pipe(
              tap((detail) => patchState(store, { detail, detailLoading: false })),
              catchError((error) => {
                patchState(store, { detailError: auditError(error), detailLoading: false });
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
