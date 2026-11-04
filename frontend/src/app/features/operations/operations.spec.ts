import { AuthStore } from '@app/store/auth/auth.store';
import { OperationsStore } from '@app/store/operations/operations.store';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import {
  ActivatedRouteSnapshot,
  provideRouter,
  Router,
  RouterStateSnapshot,
  UrlTree,
} from '@angular/router';
import { AppComponent } from '@app/app.component';
import { routes } from '@app/app.routes';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { adminGuard } from '@app/core/guards/admin.guard';
import { OperationsOverview, OperationsService } from '@app/core/operations/operations-api.service';
import { session } from '@app/testing/auth-fixtures';
import { OperationsComponent } from './operations.component';

const url = 'http://gateway.test/api/v1/admin/operations';
function metrics(index: number): OperationsService['metrics'] {
  if (index === 3)
    return { circuitBreakerState: 'OPEN', pricingAvgLatencyMs: 82, pricingCallErrors: 2 };
  if (index === 2) return { quoteCreated: 5, quoteFailed: null };
  return {};
}
const overview: OperationsOverview = {
  overallStatus: 'DEGRADED',
  checkedAt: '2026-11-03T10:00:00Z',
  services: [
    'api-gateway',
    'auth-service',
    'quote-service',
    'pricing-service',
    'notification-service',
    'audit-service',
  ].map<OperationsService>((id, i) => ({
    id,
    name: ['API Gateway', 'Auth', 'Quote', 'Pricing', 'Notification', 'Audit'][i],
    status: i === 3 ? 'DOWN' : 'UP',
    readiness: i === 3 ? 'UNKNOWN' : 'READY',
    responseTimeMs: 12,
    checkedAt: '2026-11-03T10:00:00Z',
    errorCode: i === 3 ? 'TIMEOUT' : null,
    metrics: metrics(i),
  })),
};

describe('Operations dashboard', () => {
  let store: InstanceType<typeof OperationsStore>;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://gateway.test' },
      ],
    });
    store = TestBed.inject(OperationsStore);
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });
  function render() {
    const fixture = TestBed.createComponent(OperationsComponent);
    fixture.detectChanges();
    const req = http.expectOne(url);
    expect(req.request.method).toBe('GET');
    req.flush(overview);
    fixture.detectChanges();
    return fixture;
  }
  it.each(['ADMIN', 'CUSTOMER'] as const)(
    'uses ADMIN-only navigation and existing route guard: %s',
    (role) => {
      TestBed.inject(AuthStore).loginSuccess({ ...session(), roles: [role] });
      const shell = TestBed.createComponent(AppComponent);
      shell.detectChanges();
      expect(!!shell.nativeElement.querySelector('a[href="/admin/operations"]')).toBe(
        role === 'ADMIN',
      );
      expect(!!shell.nativeElement.querySelector('a[href="/admin/audit"]')).toBe(role === 'ADMIN');
      const decision = TestBed.runInInjectionContext(() =>
        adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot),
      );
      if (role === 'ADMIN') expect(decision).toBe(true);
      else expect(TestBed.inject(Router).serializeUrl(decision as UrlTree)).toBe('/dashboard');
      expect(routes.find((r) => r.path === 'admin/operations')?.canActivate).toContain(adminGuard);
    },
  );
  it('loads one Gateway API and renders six accessible cards and partial failures', () => {
    const fixture = render();
    expect(fixture.nativeElement.querySelectorAll('article').length).toBe(6);
    for (const text of [
      'DEGRADED',
      'UP',
      'DOWN',
      '5 of 6',
      'OPEN',
      '82',
      'Quotes created',
      'Unavailable',
    ])
      expect(fixture.nativeElement.textContent).toContain(text);
    expect(
      fixture.nativeElement.querySelector('article[aria-label="Pricing service"]').textContent,
    ).toContain('TIMEOUT');
    http.verify();
    fixture.destroy();
  });
  it('shows loading and prevents overlapping manual refresh', () => {
    const fixture = TestBed.createComponent(OperationsComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Refreshing operations');
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    fixture.componentInstance.refresh();
    http.expectOne(url).flush(overview);
    http.verify();
    fixture.destroy();
  });
  it('manual Refresh fetches an updated snapshot', () => {
    const fixture = render();
    fixture.nativeElement.querySelector('button').click();
    http.expectOne(url).flush({ ...overview, overallStatus: 'UP' });
    fixture.detectChanges();
    expect(TestBed.inject(OperationsStore).overview()?.overallStatus).toBe('UP');
    fixture.destroy();
  });
  it('auto refreshes every 15 seconds, can pause and cleans up on destruction', () => {
    vi.useFakeTimers();
    const fixture = render();
    vi.advanceTimersByTime(15000);
    http.expectOne(url).flush(overview);
    fixture.componentInstance.autoRefresh.set(false);
    vi.advanceTimersByTime(30000);
    http.expectNone(url);
    fixture.componentInstance.autoRefresh.set(true);
    vi.advanceTimersByTime(15000);
    const pending = http.expectOne(url);
    fixture.destroy();
    expect(pending.cancelled).toBe(true);
    vi.advanceTimersByTime(30000);
    http.expectNone(url);
    http.verify();
  });
  it('shows a safe error and labels the retained snapshot', () => {
    const fixture = render();
    fixture.componentInstance.refresh();
    http
      .expectOne(url)
      .flush(
        { message: 'password internal-host private key' },
        { status: 500, statusText: 'Failure' },
      );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain(
      'last successful refresh',
    );
    expect(fixture.nativeElement.textContent).toContain('Operations is unavailable');
    expect(fixture.nativeElement.textContent).not.toContain('private key');
    expect(fixture.nativeElement.querySelectorAll('article').length).toBe(6);
    fixture.destroy();
  });
  it('handles unavailable metrics, allows only known metric keys and escapes text', () => {
    const fixture = TestBed.createComponent(OperationsComponent);
    http
      .expectOne(url)
      .flush({
        ...overview,
        services: [
          {
            ...overview.services[0],
            name: '<img src=x onerror=alert(1)>',
            metrics: {
              circuitBreakerState: '<script>alert(1)</script>',
              quoteCreated: null,
              arbitrarySecret: 'must not render',
            },
          },
        ],
      });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img,script')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Unavailable');
    expect(fixture.nativeElement.textContent).not.toContain('must not render');
    fixture.destroy();
  });
  it.each(['logout', 'loginFailure', 'loginSuccess', 'reset'] as const)(
    'clears data and cancels in-flight requests on $type',
    (action) => {
      const fixture = render();
      fixture.componentInstance.refresh();
      const pending = http.expectOne(url);
      action === 'loginSuccess'
        ? TestBed.inject(AuthStore).loginSuccess(session())
        : TestBed.inject(AuthStore)[action]();
      expect(pending.cancelled).toBe(true);
      expect(TestBed.inject(OperationsStore).overview()).toBeNull();
      fixture.destroy();
    },
  );
  it('cancels requests when leaving the page', () => {
    const fixture = TestBed.createComponent(OperationsComponent);
    const request = http.expectOne(url);
    fixture.destroy();
    expect(request.cancelled).toBe(true);
    expect(TestBed.inject(OperationsStore).loading()).toBe(false);
  });
  beforeEach(() => {
    const router = TestBed.inject(Router, null);
    if (router) {
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      vi.spyOn(router, 'navigate').mockResolvedValue(true);
    }
  });
});
