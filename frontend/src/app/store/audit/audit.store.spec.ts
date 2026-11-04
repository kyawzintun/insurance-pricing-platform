import { Router } from '@angular/router';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { getState } from '@ngrx/signals';
import { of, Subject, throwError } from 'rxjs';
import { AuditApiService } from '@app/core/audit/audit-api.service';
import { AuditDetail, AuditPage } from '@app/core/audit/audit.models';
import { AuthStore } from '@app/store/auth/auth.store';
import { session } from '@app/testing/auth-fixtures';
import { AuditStore, initialAuditState } from './audit.store';

const detail: AuditDetail = {
  id: 'record',
  eventId: 'event',
  eventType: 'UserDisabled',
  actorType: 'ADMIN',
  actorId: null,
  entityType: 'User',
  entityId: 'entity',
  correlationId: 'trace',
  producer: 'auth-service',
  occurredAt: '2026-10-20T00:00:00Z',
  createdAt: '2026-10-20T00:00:01Z',
  metadata: {},
};
const result: AuditPage = {
  content: [detail],
  page: 2,
  size: 10,
  totalElements: 21,
  totalPages: 3,
};

describe('Audit SignalStore', () => {
  let store: InstanceType<typeof AuditStore>;
  let api: { list: ReturnType<typeof vi.fn>; detail: ReturnType<typeof vi.fn> };
  beforeEach(() => {
    api = { list: vi.fn(() => of(result)), detail: vi.fn(() => of(detail)) };
    TestBed.configureTestingModule({ providers: [{ provide: AuditApiService, useValue: api }] });
    store = TestBed.inject(AuditStore);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });
  it('keeps applied filters/pagination and loads detail', () => {
    const query = { page: 2, size: 10, eventType: 'UserDisabled', correlationId: 'trace' };
    store.loadList({ query });
    expect(api.list).toHaveBeenCalledWith(query);
    expect(store.query()).toEqual(query);
    expect(store.result()).toEqual(result);
    expect(store.listLoading()).toBe(false);
    store.loadDetail({ id: 'record' });
    expect(api.detail).toHaveBeenCalledWith('record');
    expect(store.detail()).toEqual(detail);
  });
  it('cancels superseded list/filter and detail reads', () => {
    const oldList = new Subject<AuditPage>();
    const oldDetail = new Subject<AuditDetail>();
    api.list.mockReturnValueOnce(oldList);
    api.detail.mockReturnValueOnce(oldDetail);
    store.loadList({ query: { page: 0, size: 20, eventType: 'old' } });
    store.loadDetail({ id: 'old' });
    expect(store.listLoading()).toBe(true);
    expect(store.detailLoading()).toBe(true);
    expect(store.detail()).toBeNull();
    store.loadList({ query: { page: 2, size: 10, eventType: 'new' } });
    store.loadDetail({ id: 'new' });
    expect(oldList.observed).toBe(false);
    expect(oldDetail.observed).toBe(false);
    oldList.next({ ...result, content: [] });
    oldDetail.next({ ...detail, id: 'old' });
    expect(store.result()).toEqual(result);
    expect(store.detail()).toEqual(detail);
    expect(store.query().eventType).toBe('new');
  });
  it('settles loading on safe errors and accepts retries', () => {
    api.list.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 403, error: 'private SQL' })),
    );
    api.detail.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: 404, error: 'private SQL' })),
    );
    store.loadList({ query: { page: 0, size: 20 } });
    store.loadDetail({ id: 'missing' });
    expect(store.listLoading()).toBe(false);
    expect(store.detailLoading()).toBe(false);
    expect(store.listError()).toBe('Access denied. Audit history requires ADMIN access.');
    expect(store.detailError()).toBe('Audit record not found.');
    store.loadList({ query: { page: 2, size: 10 } });
    store.loadDetail({ id: 'record' });
    expect(store.listError()).toBeNull();
    expect(store.detailError()).toBeNull();
    expect(store.detail()).toEqual(detail);
  });
  it.each(['logout', 'loginFailure', 'loginSuccess', 'reset'] as const)(
    'cancels requests and resets on %s',
    (method) => {
      store.loadList({ query: { page: 2, size: 10 } });
      store.loadDetail({ id: 'record' });
      const pendingList = new Subject<AuditPage>();
      const pendingDetail = new Subject<AuditDetail>();
      api.list.mockReturnValue(pendingList);
      api.detail.mockReturnValue(pendingDetail);
      store.loadList({ query: { page: 1, size: 20 } });
      store.loadDetail({ id: 'pending' });
      const auth = TestBed.inject(AuthStore);
      method === 'loginSuccess' ? auth.loginSuccess(session()) : auth[method]();
      expect(pendingList.observed).toBe(false);
      expect(pendingDetail.observed).toBe(false);
      pendingList.next(result);
      pendingDetail.next(detail);
      expect(getState(store)).toEqual(initialAuditState);
      api.list.mockReturnValue(of(result));
      store.loadList({ query: { page: 2, size: 10 } });
      expect(store.result()).toEqual(result);
      store.reset();
      expect(getState(store)).toEqual(initialAuditState);
    },
  );
});
