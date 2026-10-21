import { inject, Injectable } from '@angular/core';
import { createAction, createFeature, createReducer, on, props } from '@ngrx/store';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, map, of, switchMap, takeUntil } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { AuditDetail, AuditPage, AuditQuery } from '@app/core/audit/audit.models';
import { AuditApiService } from '@app/core/audit/audit-api.service';
import { AuthActions } from '@app/store/auth/auth.actions';

export const AuditActions = {
  list: createAction('[Audit] List', props<{ query: AuditQuery }>()),
  listed: createAction('[Audit] Listed', props<{ result: AuditPage }>()),
  listFailed: createAction('[Audit] List Failed', props<{ message: string }>()),
  detail: createAction('[Audit] Detail', props<{ id: string }>()),
  detailed: createAction('[Audit] Detailed', props<{ detail: AuditDetail }>()),
  detailFailed: createAction('[Audit] Detail Failed', props<{ message: string }>()),
};

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

export const auditFeature = createFeature({
  name: 'audit',
  reducer: createReducer(
    initialAuditState,
    on(AuditActions.list, (s, { query }) => ({
      ...s,
      query,
      result: { ...initialAuditState.result, page: query.page, size: query.size },
      listLoading: true,
      listError: null,
    })),
    on(AuditActions.listed, (s, { result }) => ({ ...s, result, listLoading: false })),
    on(AuditActions.listFailed, (s, { message }) => ({
      ...s,
      listLoading: false,
      listError: message,
    })),
    on(AuditActions.detail, (s) => ({
      ...s,
      detail: null,
      detailLoading: true,
      detailError: null,
    })),
    on(AuditActions.detailed, (s, { detail }) => ({ ...s, detail, detailLoading: false })),
    on(AuditActions.detailFailed, (s, { message }) => ({
      ...s,
      detailLoading: false,
      detailError: message,
    })),
    on(
      AuthActions.logout,
      AuthActions.loginFailure,
      AuthActions.loginSuccess,
      () => initialAuditState,
    ),
  ),
});

export function auditError(error: unknown): string {
  const status = error instanceof HttpErrorResponse ? error.status : 0;
  if (status === 403) return 'Access denied. Audit history requires ADMIN access.';
  if (status === 404) return 'Audit record not found.';
  if (status === 400) return 'Invalid audit filters. Check IDs, UTC timestamps and date range.';
  return 'Audit history is unavailable. Please try again.';
}

@Injectable()
export class AuditEffects {
  private readonly actions = inject(Actions);
  private readonly api = inject(AuditApiService);
  private readonly sessionChanged = this.actions.pipe(
    ofType(AuthActions.logout, AuthActions.loginFailure, AuthActions.loginSuccess),
  );

  readonly list = createEffect(() =>
    this.actions.pipe(
      ofType(AuditActions.list),
      switchMap(({ query }) =>
        this.api.list(query).pipe(
          map((result) => AuditActions.listed({ result })),
          catchError((error) => of(AuditActions.listFailed({ message: auditError(error) }))),
          takeUntil(this.sessionChanged),
        ),
      ),
    ),
  );

  readonly detail = createEffect(() =>
    this.actions.pipe(
      ofType(AuditActions.detail),
      switchMap(({ id }) =>
        this.api.detail(id).pipe(
          map((detail) => AuditActions.detailed({ detail })),
          catchError((error) => of(AuditActions.detailFailed({ message: auditError(error) }))),
          takeUntil(this.sessionChanged),
        ),
      ),
    ),
  );
}
