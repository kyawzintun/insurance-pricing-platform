import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, ActivatedRouteSnapshot, RouterStateSnapshot, Router, UrlTree, convertToParamMap, provideRouter } from '@angular/router';
import { provideHttpClient, HttpErrorResponse } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideState, provideStore, Store } from '@ngrx/store';
import { provideEffects } from '@ngrx/effects';
import { BehaviorSubject, Subject, of, firstValueFrom, Observable } from 'rxjs';
import { auditFeature, AuditActions, AuditEffects, auditError } from '@app/store/audit/audit.store';
import { authFeature } from '@app/store/auth/auth.reducer';
import { AuthActions } from '@app/store/auth/auth.actions';
import { session } from '@app/testing/auth-fixtures';
import { AuditListComponent } from './audit-list.component';
import { AuditDetailComponent } from './audit-detail.component';
import { AuditApiService } from '@app/core/audit/audit-api.service';
import { AuditDetail } from '@app/core/audit/audit.models';
import { adminGuard } from '@app/core/guards/admin.guard';
import { AppComponent } from '@app/app.component';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { routes } from '@app/app.routes';
const row: AuditDetail = { id: 'record-a', eventId: 'event-a', eventType: 'UserDisabled', actorType: 'ADMIN', actorId: 'actor-a', entityType: 'User', entityId: 'entity-a', correlationId: 'trace-a', producer: 'auth-service', occurredAt: '2026-10-20T00:00:00Z', createdAt: '2026-10-20T00:00:01Z', metadata: { userId: '<img src=x onerror=alert(1)><script>alert(1)</script>' } };
const result = { content: [row], page: 0, size: 20, totalElements: 41, totalPages: 3 };
describe('Audit pages and access', () => {
  let store: Store;
  let query: BehaviorSubject<ReturnType<typeof convertToParamMap>>;
  beforeEach(() => {
    query = new BehaviorSubject(convertToParamMap({ page: '0', size: '20', correlationId: 'trace-a' }));
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideStore(), provideState(authFeature), provideState(auditFeature), provideHttpClient(), provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { queryParamMap: query, paramMap: of(convertToParamMap({ id: row.id })), snapshot: { queryParams: { correlationId: 'trace-a' } } } }] });
    store = TestBed.inject(Store);
  });
  it.each(['ADMIN','CUSTOMER'] as const)('shows nav and allows route only for ADMIN: %s', role => {
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), roles: [role] } }));
    const shell=TestBed.createComponent(AppComponent); shell.detectChanges();
    expect(!!shell.nativeElement.querySelector('a[href="/admin/audit"]')).toBe(role === 'ADMIN');
    const allowed=TestBed.runInInjectionContext(() => adminGuard({} as ActivatedRouteSnapshot,{} as RouterStateSnapshot));
    if(role === 'ADMIN') expect(allowed).toBe(true);
    else expect(TestBed.inject(Router).serializeUrl(allowed as UrlTree)).toBe('/dashboard');
    expect(routes.filter(r => r.path?.startsWith('admin/audit')).every(r => r.canActivate?.includes(adminGuard))).toBe(true);
  });
  it('redirects signed-out and failed expired sessions to login', async () => {
    const run=() => TestBed.runInInjectionContext(() => adminGuard({} as ActivatedRouteSnapshot,{} as RouterStateSnapshot));
    expect(TestBed.inject(Router).serializeUrl(run() as UrlTree)).toBe('/login');
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), roles: ['ADMIN'], expiresAt: 1 } }));
    const decision=firstValueFrom(run() as Observable<UrlTree>);
    TestBed.inject(HttpTestingController).expectOne(r=>r.url.endsWith('/auth/refresh')).flush({}, {status:401,statusText:'Unauthorized'});
    expect(TestBed.inject(Router).serializeUrl(await decision)).toBe('/login');
  });
  it('loads URL filters, displays loading, rows, and paginates with applied filters', () => {
    const fixture=TestBed.createComponent(AuditListComponent); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Loading audit history');
    expect(store.selectSignal(auditFeature.selectQuery)()).toEqual({ page:0,size:20,correlationId:'trace-a' });
    store.dispatch(AuditActions.listed({ result }));fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('UserDisabled');
    expect(fixture.nativeElement.textContent).not.toContain('<img');
    const navigate=vi.spyOn(TestBed.inject(Router),'navigate').mockResolvedValue(true);
    fixture.componentInstance.form.controls['correlationId'].setValue('unapplied');
    fixture.componentInstance.page(1);
    expect(navigate.mock.lastCall?.[1]?.queryParams).toEqual({ page:1,size:20,correlationId:'trace-a' });
  });
  it('applies and clears filters in URL, resetting page', () => {
    const fixture=TestBed.createComponent(AuditListComponent);
    const navigate=vi.spyOn(TestBed.inject(Router),'navigate').mockResolvedValue(true);
    fixture.componentInstance.form.controls['eventType'].setValue(' UserDisabled ');
    fixture.componentInstance.apply();
    expect(navigate.mock.lastCall?.[1]?.queryParams).toMatchObject({ page:0,size:20,eventType:'UserDisabled' });
    fixture.componentInstance.clear();
    expect(navigate.mock.lastCall?.[1]?.queryParams).toEqual({ page:0,size:20 });
    expect(fixture.componentInstance.form.controls['eventType'].value).toBe('');
  });
  it('validates IDs and UTC range without navigation', () => {
    const c=TestBed.createComponent(AuditListComponent).componentInstance;
    const navigate=vi.spyOn(TestBed.inject(Router),'navigate');
    c.form.controls['actorId'].setValue('invalid');c.apply();expect(c.validation()).toContain('UUID');
    c.form.controls['actorId'].setValue('');c.form.controls['from'].setValue('yesterday');c.apply();expect(c.validation()).toContain('UTC');
    c.form.controls['from'].setValue('2026-10-21T00:00:00Z');c.form.controls['to'].setValue('2026-10-20T00:00:00Z');c.apply();expect(c.validation()).toContain('before');
    expect(navigate).not.toHaveBeenCalled();
  });
  it('renders empty and error states', () => {
    const fixture=TestBed.createComponent(AuditListComponent);
    store.dispatch(AuditActions.listed({ result:{ ...result,content:[],totalElements:0,totalPages:0 } }));fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('No audit records');
    store.dispatch(AuditActions.listFailed({ message:'Access denied' }));fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Access denied');
  });
  it('loads detail and renders metadata as safe text, with empty and not-found states', () => {
    const fixture=TestBed.createComponent(AuditDetailComponent);fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Loading audit record');
    store.dispatch(AuditActions.detailed({ detail:row }));fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('pre').textContent).toContain('<script>');
    expect(fixture.nativeElement.querySelector('img,script')).toBeNull();
    fixture.componentInstance.metadataExpanded.set(false);fixture.detectChanges();expect(fixture.nativeElement.querySelector('pre')).toBeNull();
    store.dispatch(AuditActions.detailed({ detail:{ ...row,metadata:{} } }));fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('No metadata');
    store.dispatch(AuditActions.detailFailed({ message:auditError(new HttpErrorResponse({ status:404 })) }));fixture.detectChanges();expect(fixture.nativeElement.textContent).toContain('Audit record not found');
  });
  it('clears sensitive state on all session changes', () => {
    for(const action of [AuthActions.logout(),AuthActions.loginFailure(),AuthActions.loginSuccess({ session:session() })]) {
      store.dispatch(AuditActions.detailed({ detail:row }));store.dispatch(AuditActions.listed({ result }));store.dispatch(action);
      expect(store.selectSignal(auditFeature.selectDetail)()).toBeNull();expect(store.selectSignal(auditFeature.selectResult)().content).toEqual([]);
    }
  });
});
describe('Audit HTTP and effects', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers:[provideHttpClient(),provideHttpClientTesting(),{ provide:GATEWAY_URL,useValue:'http://gateway.test' }] }));
  it('maps exact filters and pagination into GET parameters and escapes detail ID', () => {
    const api=TestBed.inject(AuditApiService), http=TestBed.inject(HttpTestingController);
    api.list({ page:2,size:50,eventType:'UserDisabled',correlationId:'trace +/?' }).subscribe();
    const req=http.expectOne(r => r.url === 'http://gateway.test/api/v1/admin/audit');
    expect(req.request.method).toBe('GET');expect(req.request.params.get('page')).toBe('2');expect(req.request.params.get('correlationId')).toBe('trace +/?');req.flush(result);
    api.detail('a/b').subscribe();http.expectOne('http://gateway.test/api/v1/admin/audit/a%2Fb').flush(row);http.verify();
  });
  it.each([400,403,404,500])('uses safe local messages for %s', status => {
    const message=auditError(new HttpErrorResponse({ status,error:'private SQL secret' }));expect(message).not.toContain('secret');expect(message.length).toBeGreaterThan(10);
  });
});
describe('Audit effect cancellation', () => {
  it('loads list/detail and cancels pending reads on logout', () => {
    const pending=new Subject<typeof result>();const api={ list:vi.fn(() => pending),detail:vi.fn(() => of(row)) };
    TestBed.configureTestingModule({ providers:[provideStore(),provideState(auditFeature),provideEffects(AuditEffects),{ provide:AuditApiService,useValue:api }] });
    const store=TestBed.inject(Store);
    store.dispatch(AuditActions.detail({ id:row.id }));expect(store.selectSignal(auditFeature.selectDetail)()).toEqual(row);
    store.dispatch(AuditActions.list({ query:{ page:0,size:20 } }));expect(pending.observed).toBe(true);
    pending.next(result);expect(store.selectSignal(auditFeature.selectResult)()).toEqual(result);
    store.dispatch(AuthActions.logout());expect(pending.observed).toBe(false);pending.next(result);expect(store.selectSignal(auditFeature.selectResult)().content).toEqual([]);
  });
});
