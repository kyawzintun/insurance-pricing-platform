import { patchState } from '@ngrx/signals';
import { unprotected } from '@ngrx/signals/testing';
import { AuthStore } from '@app/store/auth/auth.store';
import { QuoteStore } from '@app/store/quotes/quote.store';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { QuoteNewComponent } from '@app/features/quotes/new/quote-new.component';
import { QuoteHistoryComponent } from '@app/features/quotes/history/quote-history.component';
import { QuoteDetailComponent } from '@app/features/quotes/detail/quote-detail.component';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { customerGuard } from '@app/core/guards/customer.guard';
import { AppComponent } from '@app/app.component';
import { quoteInput, storedQuote, quotePage } from '@app/testing/quote-fixtures';
import { session } from '@app/testing/auth-fixtures';
import { routes } from '@app/app.routes';

describe('Quote pages', () => {
  let api: {
    createQuote: ReturnType<typeof vi.fn>;
    getQuotes: ReturnType<typeof vi.fn>;
    getQuote: ReturnType<typeof vi.fn>;
    getVehicleBrands: ReturnType<typeof vi.fn>;
    getVehicleModels: ReturnType<typeof vi.fn>;
  };
  let store: InstanceType<typeof QuoteStore>;
  beforeEach(() => {
    api = {
      createQuote: vi.fn(() => new Subject()),
      getQuotes: vi.fn(() => new Subject()),
      getQuote: vi.fn(() => new Subject()),
      getVehicleBrands: vi.fn(() =>
        of([
          { id: 'brand-a', name: 'API Brand' },
          { id: 'brand-b', name: 'Another brand' },
        ]),
      ),
      getVehicleModels: vi.fn(() => of([{ id: 'model-a', name: 'API Model' }])),
    };
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        { provide: QuoteApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'quote-a' })) },
        },
      ],
    });
    store = TestBed.inject(QuoteStore);
  });
  it('loads catalog and clears selected model when brand changes', () => {
    const fixture = TestBed.createComponent(QuoteNewComponent);
    const c = fixture.componentInstance;
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('API Brand');
    c.form.controls.vehicleBrandId.setValue('brand-a');
    c.form.controls.vehicleModelId.setValue('model-a');
    c.form.controls.vehicleBrandId.setValue('brand-b');
    expect(c.form.controls.vehicleModelId.value).toBe('');
    expect(api.getVehicleModels).toHaveBeenLastCalledWith('brand-b');
  });
  it('cancels stale models and safely handles an unavailable brand', () => {
    const old = new Subject<never[]>();
    api.getVehicleModels
      .mockReturnValueOnce(old)
      .mockReturnValueOnce(
        throwError(() => new HttpErrorResponse({ status: 404, error: 'private details' })),
      );
    const fixture = TestBed.createComponent(QuoteNewComponent);
    const c = fixture.componentInstance;
    c.form.controls.vehicleBrandId.setValue('brand-a');
    expect(c.loadingModels()).toBe(true);
    c.form.controls.vehicleBrandId.setValue('brand-b');
    expect(old.observed).toBe(false);
    expect(c.models()).toEqual([]);
    expect(c.loadingModels()).toBe(false);
    expect(c.catalogError()).toBe('Selected vehicle is no longer available.');
  });
  it.each([
    ['dateOfBirth', ''],
    ['dateOfBirth', '2999-01-01'],
    ['dateOfBirth', '2026-02-30'],
    ['drivingExperienceYears', -1],
    ['drivingExperienceYears', 1.5],
    ['previousClaimsCount', -1],
    ['vehicleManufacturingYear', 1885],
    ['vehicleManufacturingYear', 2999],
    ['vehicleValue', 0],
    ['vehicleValue', 1.123],
    ['engineSizeCc', 0],
    ['engineSizeCc', 1.5],
    ['coverageType', ''],
  ])('validates %s with value %s', (field, value) => {
    const c = TestBed.createComponent(QuoteNewComponent).componentInstance;
    const control = c.form.get(String(field))!;
    control.setValue(value);
    expect(control.invalid).toBe(true);
  });
  it('rejects empty forms and prevents duplicate valid submission while pending', () => {
    const fixture = TestBed.createComponent(QuoteNewComponent);
    const c = fixture.componentInstance;
    const create = api.createQuote;
    c.submit();
    expect(create).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Select coverage.');
    c.form.setValue(quoteInput);
    c.submit();
    c.submit();
    expect(create).toHaveBeenCalledExactlyOnceWith(quoteInput);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
  });
  it('shows safe creation failures', () => {
    const fixture = TestBed.createComponent(QuoteNewComponent);
    patchState(unprotected(TestBed.inject(QuoteStore)), {
      createStatus: 'error',
      createError: 'Please check your quote information.',
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Please check your quote information.');
  });
  it('renders history and sends previous/next page requests', () => {
    const fixture = TestBed.createComponent(QuoteHistoryComponent);
    patchState(unprotected(TestBed.inject(QuoteStore)), {
      historyStatus: 'success',
      history: quotePage,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Q-HISTORICAL');
    expect(fixture.nativeElement.textContent).toContain('Page 1 of 2');
    const buttons = fixture.nativeElement.querySelectorAll('button');
    expect(buttons[0].disabled).toBe(true);
    const load = api.getQuotes;
    buttons[1].click();
    expect(load).toHaveBeenCalledWith(1, 20);
    patchState(unprotected(TestBed.inject(QuoteStore)), {
      historyStatus: 'success',
      history: { ...quotePage, page: 1 },
    });
    fixture.detectChanges();
    const next = fixture.nativeElement.querySelectorAll('button');
    expect(next[1].disabled).toBe(true);
    next[0].click();
    expect(load).toHaveBeenLastCalledWith(0, 20);
  });
  it('renders historical snapshots without current catalog requests and toggles local breakdown', () => {
    const fixture = TestBed.createComponent(QuoteDetailComponent);
    patchState(unprotected(TestBed.inject(QuoteStore)), {
      detailStatus: 'success',
      selectedQuote: storedQuote,
    });
    fixture.detectChanges();
    const text = fixture.nativeElement.textContent;
    for (const value of [
      'Historical brand',
      'Historical model',
      'Apr 20, 1995',
      '8 years',
      'Stored coverage adjustment',
      '11,200.00',
      '1.4000',
    ])
      expect(text).toContain(value);
    expect(api.getVehicleBrands).not.toHaveBeenCalled();
    expect(api.getVehicleModels).not.toHaveBeenCalled();
    fixture.nativeElement.querySelector('button').click();
    fixture.detectChanges();
    expect(fixture.componentInstance.showBreakdown()).toBe(false);
  });
  it('renders a safe missing detail and removes the old snapshot', () => {
    const fixture = TestBed.createComponent(QuoteDetailComponent);
    patchState(unprotected(TestBed.inject(QuoteStore)), {
      detailStatus: 'error',
      selectedQuote: null,
      detailError: 'Quote not found.',
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Quote not found.');
    expect(fixture.nativeElement.textContent).not.toContain('Q-HISTORICAL');
  });
  it('protects every quote route and hides customer navigation for ADMIN-only', () => {
    expect(
      routes
        .filter((r) => r.path?.startsWith('quotes'))
        .every((r) => r.canActivate?.includes(customerGuard)),
    ).toBe(true);
    const guard = () => TestBed.runInInjectionContext(() => (customerGuard as () => unknown)());
    expect(TestBed.inject(Router).serializeUrl(guard() as never)).toBe('/login');
    TestBed.inject(AuthStore).loginSuccess(session());
    expect(guard()).toBe(true);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('New Quote');
    TestBed.inject(AuthStore).loginSuccess({ ...session(), roles: ['ADMIN'] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('New Quote');
    expect(TestBed.inject(Router).serializeUrl(guard() as never)).toBe('/dashboard');
    TestBed.inject(AuthStore).logout();
    expect(TestBed.inject(Router).serializeUrl(guard() as never)).toBe('/login');
  });
  beforeEach(() => {
    const router = TestBed.inject(Router, null);
    if (router) {
      vi.spyOn(router, 'navigateByUrl').mockResolvedValue(true);
      vi.spyOn(router, 'navigate').mockResolvedValue(true);
    }
  });
});
