import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { getState } from '@ngrx/signals';
import { AuthStore } from '@app/store/auth/auth.store';
import { QuoteStore, initialQuoteState } from './quote.store';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { quoteInput, storedQuote, quotePage } from '@app/testing/quote-fixtures';
import { session } from '@app/testing/auth-fixtures';

describe('Quote SignalStore', () => {
  let store: InstanceType<typeof QuoteStore>;
  let api: {
    createQuote: ReturnType<typeof vi.fn>;
    getQuote: ReturnType<typeof vi.fn>;
    getQuotes: ReturnType<typeof vi.fn>;
  };
  beforeEach(() => {
    api = {
      createQuote: vi.fn(() => of(storedQuote)),
      getQuote: vi.fn(() => of(storedQuote)),
      getQuotes: vi.fn(() => of(quotePage)),
    };
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: QuoteApiService, useValue: api }],
    });
    store = TestBed.inject(QuoteStore);
    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
  });
  it('loads creation, paginated history and detail from the API', () => {
    expect(getState(store)).toEqual(initialQuoteState);
    store.createQuote({ request: quoteInput });
    expect(store.currentQuote()).toEqual(storedQuote);
    expect(store.createStatus()).toBe('success');
    expect(TestBed.inject(Router).navigate).toHaveBeenCalledWith(['/quotes', storedQuote.id]);
    store.loadQuotes({ page: 1, size: 20 });
    expect(api.getQuotes).toHaveBeenCalledWith(1, 20);
    expect(store.history()).toEqual(quotePage);
    store.loadQuote({ id: storedQuote.id });
    expect(api.getQuote).toHaveBeenCalledWith(storedQuote.id);
    expect(store.selectedQuote()).toEqual(storedQuote);
  });
  it('ignores duplicate creation while pending', () => {
    const pending = new Subject<typeof storedQuote>();
    api.createQuote.mockReturnValue(pending);
    store.createQuote({ request: quoteInput });
    store.createQuote({ request: quoteInput });
    expect(api.createQuote).toHaveBeenCalledTimes(1);
    expect(store.createStatus()).toBe('pending');
    pending.next(storedQuote);
    pending.complete();
    expect(store.createStatus()).toBe('success');
  });
  it.each([
    [400, 'Please check your quote information.'],
    [502, 'Unable to calculate the premium right now.'],
    [503, 'Pricing service is temporarily unavailable. Please try again.'],
  ])('maps %s creation errors and allows retry', (status, message) => {
    api.createQuote.mockReturnValueOnce(
      throwError(() => new HttpErrorResponse({ status: Number(status), error: 'private SQL' })),
    );
    store.createQuote({ request: quoteInput });
    expect(store.createError()).toBe(message);
    expect(store.createStatus()).toBe('error');
    store.createQuote({ request: quoteInput });
    expect(store.createStatus()).toBe('success');
    expect(store.createError()).toBeNull();
  });
  it('clears stale detail and maps missing records safely', () => {
    store.loadQuote({ id: 'a' });
    api.getQuote.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 404 })));
    store.loadQuote({ id: 'missing' });
    expect(store.selectedQuote()).toBeNull();
    expect(store.detailError()).toBe('Quote not found.');
  });
  it('cancels superseded history and detail requests', () => {
    const history = new Subject<typeof quotePage>();
    const detail = new Subject<typeof storedQuote>();
    api.getQuotes.mockReturnValueOnce(history);
    api.getQuote.mockReturnValueOnce(detail);
    store.loadQuotes({ page: 0, size: 20 });
    store.loadQuote({ id: 'old' });
    expect(store.historyStatus()).toBe('pending');
    expect(store.detailStatus()).toBe('pending');
    store.loadQuotes({ page: 1, size: 20 });
    store.loadQuote({ id: 'new' });
    expect(history.observed).toBe(false);
    expect(detail.observed).toBe(false);
    history.next({ ...quotePage, page: 99 });
    detail.next({ ...storedQuote, id: 'stale' });
    expect(store.history()).toEqual(quotePage);
    expect(store.selectedQuote()).toEqual(storedQuote);
  });
  it.each(['logout', 'loginFailure', 'loginSuccess', 'reset'] as const)(
    'cancels all requests and clears protected state on %s',
    (method) => {
      store.loadQuotes({ page: 0, size: 20 });
      store.loadQuote({ id: 'a' });
      const pending = new Subject();
      api.createQuote.mockReturnValue(pending);
      api.getQuote.mockReturnValue(pending);
      api.getQuotes.mockReturnValue(pending);
      store.createQuote({ request: quoteInput });
      store.loadQuotes({ page: 0, size: 20 });
      store.loadQuote({ id: 'a' });
      const auth = TestBed.inject(AuthStore);
      method === 'loginSuccess' ? auth.loginSuccess(session()) : auth[method]();
      expect(pending.observed).toBe(false);
      pending.next(storedQuote);
      expect(getState(store)).toEqual(initialQuoteState);
      api.getQuote.mockReturnValue(of(storedQuote));
      store.loadQuote({ id: 'new-session' });
      expect(store.selectedQuote()).toEqual(storedQuote);
    },
  );
  it('reports history failures and resets independently', () => {
    api.getQuotes.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 500, error: 'private SQL' })),
    );
    store.loadQuotes({ page: 2, size: 10 });
    expect(store.historyStatus()).toBe('error');
    expect(store.historyError()).not.toContain('SQL');
    store.reset();
    expect(getState(store)).toEqual(initialQuoteState);
  });
});
