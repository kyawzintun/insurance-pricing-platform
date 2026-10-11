import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { provideState, provideStore, Store } from '@ngrx/store';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { QuoteNewComponent } from '@app/features/quotes/new/quote-new.component';
import { QuoteHistoryComponent } from '@app/features/quotes/history/quote-history.component';
import { QuoteDetailComponent } from '@app/features/quotes/detail/quote-detail.component';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { quoteFeature } from '@app/store/quotes/quote.reducer';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { authFeature } from '@app/store/auth/auth.reducer';
import { AuthActions } from '@app/store/auth/auth.actions';
import { customerGuard } from '@app/core/guards/customer.guard';
import { AppComponent } from '@app/app.component';
import { quoteInput, storedQuote, quotePage } from '@app/testing/quote-fixtures';
import { session } from '@app/testing/auth-fixtures';
import { routes } from '@app/app.routes';

describe('Quote pages', () => {
  let api: {
    getVehicleBrands: ReturnType<typeof vi.fn>;
    getVehicleModels: ReturnType<typeof vi.fn>;
  };
  let store: Store;
  beforeEach(() => {
    api = {
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
        provideStore(),
        provideState(authFeature),
        provideState(quoteFeature),
        { provide: QuoteApiService, useValue: api },
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'quote-a' })) },
        },
      ],
    });
    store = TestBed.inject(Store);
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
    const dispatch = vi.spyOn(store, 'dispatch');
    c.submit();
    expect(dispatch).not.toHaveBeenCalled();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Select coverage.');
    c.form.setValue(quoteInput);
    c.submit();
    c.submit();
    expect(dispatch).toHaveBeenCalledExactlyOnceWith(
      QuoteActions.createQuote({ request: quoteInput }),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('button[type=submit]').disabled).toBe(true);
  });
  it('shows safe creation failures', () => {
    const fixture = TestBed.createComponent(QuoteNewComponent);
    store.dispatch(
      QuoteActions.createQuoteFailure({ message: 'Please check your quote information.' }),
    );
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Please check your quote information.');
  });
  it('renders history and sends previous/next page actions', () => {
    const fixture = TestBed.createComponent(QuoteHistoryComponent);
    store.dispatch(QuoteActions.loadQuotesSuccess({ result: quotePage }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Q-HISTORICAL');
    expect(fixture.nativeElement.textContent).toContain('Page 1 of 2');
    const buttons = fixture.nativeElement.querySelectorAll('button');
    expect(buttons[0].disabled).toBe(true);
    const dispatch = vi.spyOn(store, 'dispatch');
    buttons[1].click();
    expect(dispatch).toHaveBeenCalledWith(QuoteActions.loadQuotes({ page: 1, size: 20 }));
    store.dispatch(QuoteActions.loadQuotesSuccess({ result: { ...quotePage, page: 1 } }));
    fixture.detectChanges();
    const next = fixture.nativeElement.querySelectorAll('button');
    expect(next[1].disabled).toBe(true);
    next[0].click();
    expect(dispatch).toHaveBeenLastCalledWith(QuoteActions.loadQuotes({ page: 0, size: 20 }));
  });
  it('renders historical snapshots without current catalog requests and toggles local breakdown', () => {
    const fixture = TestBed.createComponent(QuoteDetailComponent);
    store.dispatch(QuoteActions.loadQuoteSuccess({ quote: storedQuote }));
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
    store.dispatch(QuoteActions.loadQuoteFailure({ message: 'Quote not found.' }));
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
    store.dispatch(AuthActions.loginSuccess({ session: session() }));
    expect(guard()).toBe(true);
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('New Quote');
    store.dispatch(AuthActions.loginSuccess({ session: { ...session(), roles: ['ADMIN'] } }));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).not.toContain('New Quote');
    expect(TestBed.inject(Router).serializeUrl(guard() as never)).toBe('/dashboard');
    store.dispatch(AuthActions.logout());
    expect(TestBed.inject(Router).serializeUrl(guard() as never)).toBe('/login');
  });
});
