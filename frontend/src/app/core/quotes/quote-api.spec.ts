import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideStore, provideState, Store } from '@ngrx/store';
import { provideRouter } from '@angular/router';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { GATEWAY_URL } from '@app/core/auth/gateway.config';
import { authInterceptor } from '@app/core/interceptors/auth.interceptor';
import { AuthActions } from '@app/store/auth/auth.actions';
import { authFeature } from '@app/store/auth/auth.reducer';
import { session } from '@app/testing/auth-fixtures';
import { quoteInput, storedQuote, quotePage } from '@app/testing/quote-fixtures';

describe('Quote Gateway API contracts', () => {
  let api: QuoteApiService;
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideStore(),
        provideState(authFeature),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: GATEWAY_URL, useValue: 'http://gateway.test' },
      ],
    });
    api = TestBed.inject(QuoteApiService);
    http = TestBed.inject(HttpTestingController);
    TestBed.inject(Store).dispatch(AuthActions.loginSuccess({ session: session() }));
  });
  afterEach(() => http.verify());
  it('posts only quote inputs and attaches JWT using the existing interceptor', () => {
    api.createQuote(quoteInput).subscribe();
    const request = http.expectOne('http://gateway.test/api/v1/quotes');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual(quoteInput);
    expect(Object.keys(request.request.body)).toHaveLength(9);
    expect(request.request.body.customerId).toBeUndefined();
    expect(request.request.body.premium).toBeUndefined();
    expect(request.request.body.status).toBeUndefined();
    expect(request.request.headers.get('Authorization')).toMatch(/^Bearer /);
    request.flush(storedQuote);
  });
  it('uses backend pagination without an ownership override', () => {
    api.getQuotes(1, 20).subscribe();
    const request = http.expectOne('http://gateway.test/api/v1/quotes?page=1&size=20');
    expect(request.request.params.keys()).toEqual(['page', 'size']);
    request.flush(quotePage);
  });
  it('loads the stored detail without catalog or Pricing calls', () => {
    api.getQuote('quote-a').subscribe();
    http.expectOne('http://gateway.test/api/v1/quotes/quote-a').flush(storedQuote);
  });
  it('loads brands and selected brand models from Gateway', () => {
    api.getVehicleBrands().subscribe();
    http.expectOne('http://gateway.test/api/v1/vehicles/brands').flush([]);
    api.getVehicleModels('brand-a').subscribe();
    http.expectOne('http://gateway.test/api/v1/vehicles/brands/brand-a/models').flush([]);
  });
});
