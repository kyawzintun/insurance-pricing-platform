import { TestBed } from '@angular/core/testing';
import { Actions } from '@ngrx/effects';
import { Action } from '@ngrx/store';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { of, Subject, throwError } from 'rxjs';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { quoteFeature, initialQuoteState } from '@app/store/quotes/quote.reducer';
import { QuoteEffects } from '@app/store/quotes/quote.effects';
import { AuthActions } from '@app/store/auth/auth.actions';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { quoteInput, storedQuote, quotePage } from '@app/testing/quote-fixtures';
import { session } from '@app/testing/auth-fixtures';

describe('Shared quote state', () => {
  it('stores creation, history, detail and exposes selectors', () => {
    let state = quoteFeature.reducer(undefined, { type: 'init' });
    expect(state).toEqual(initialQuoteState);
    state = quoteFeature.reducer(state, QuoteActions.createQuote({ request: quoteInput }));
    expect(state.createStatus).toBe('pending');
    state = quoteFeature.reducer(state, QuoteActions.createQuoteSuccess({ quote: storedQuote }));
    expect(state.currentQuote).toEqual(storedQuote);
    state = quoteFeature.reducer(state, QuoteActions.loadQuotesSuccess({ result: quotePage }));
    state = quoteFeature.reducer(state, QuoteActions.loadQuoteSuccess({ quote: storedQuote }));
    expect(quoteFeature.selectHistory.projector(state)).toEqual(quotePage);
    expect(quoteFeature.selectSelectedQuote.projector(state)).toEqual(storedQuote);
    state = quoteFeature.reducer(state, QuoteActions.loadQuote({ id: 'missing' }));
    expect(state.selectedQuote).toBeNull();
  });
  it('clears private quote data on logout, login failure and account change', () => {
    const state = {
      ...initialQuoteState,
      selectedQuote: storedQuote,
      currentQuote: storedQuote,
      history: quotePage,
    };
    for (const action of [
      AuthActions.logout(),
      AuthActions.loginFailure(),
      AuthActions.loginSuccess({ session: session() }),
    ]) {
      expect(quoteFeature.reducer(state, action)).toEqual(initialQuoteState);
    }
  });
});

describe('Quote effects', () => {
  let actions: Subject<Action>;
  let effects: QuoteEffects;
  let api: {
    createQuote: ReturnType<typeof vi.fn>;
    getQuote: ReturnType<typeof vi.fn>;
    getQuotes: ReturnType<typeof vi.fn>;
  };
  const router = { navigate: vi.fn().mockResolvedValue(true) };
  beforeEach(() => {
    actions = new Subject();
    api = {
      createQuote: vi.fn(() => of(storedQuote)),
      getQuote: vi.fn(() => of(storedQuote)),
      getQuotes: vi.fn(() => of(quotePage)),
    };
    TestBed.configureTestingModule({
      providers: [
        QuoteEffects,
        { provide: Actions, useValue: actions },
        { provide: QuoteApiService, useValue: api },
        { provide: Router, useValue: router },
      ],
    });
    effects = TestBed.inject(QuoteEffects);
    router.navigate.mockClear();
  });
  afterEach(() => actions.complete());
  it('creates once while pending and navigates after success', () => {
    const pending = new Subject<typeof storedQuote>();
    api.createQuote.mockReturnValue(pending);
    const output: Action[] = [];
    effects.create.subscribe((a) => output.push(a));
    effects.afterCreate.subscribe();
    actions.next(QuoteActions.createQuote({ request: quoteInput }));
    actions.next(QuoteActions.createQuote({ request: quoteInput }));
    expect(api.createQuote).toHaveBeenCalledTimes(1);
    expect(api.createQuote).toHaveBeenCalledWith(quoteInput);
    pending.next(storedQuote);
    expect(output).toEqual([QuoteActions.createQuoteSuccess({ quote: storedQuote })]);
    actions.next(output[0]!);
    expect(router.navigate).toHaveBeenCalledWith(['/quotes', storedQuote.id]);
  });
  it.each([
    [400, 'Please check your quote information.'],
    [502, 'Unable to calculate the premium right now.'],
    [503, 'Pricing service is temporarily unavailable. Please try again.'],
  ])('maps %s creation errors safely', (status, message) => {
    api.createQuote.mockReturnValue(
      throwError(
        () => new HttpErrorResponse({ status: Number(status), error: { message: 'private SQL' } }),
      ),
    );
    const output: Action[] = [];
    effects.create.subscribe((a) => output.push(a));
    actions.next(QuoteActions.createQuote({ request: quoteInput }));
    expect(output).toEqual([QuoteActions.createQuoteFailure({ message: String(message) })]);
  });
  it('loads history and detail through the API service', () => {
    const output: Action[] = [];
    effects.history.subscribe((a) => output.push(a));
    effects.detail.subscribe((a) => output.push(a));
    actions.next(QuoteActions.loadQuotes({ page: 1, size: 20 }));
    actions.next(QuoteActions.loadQuote({ id: 'quote-a' }));
    expect(api.getQuotes).toHaveBeenCalledWith(1, 20);
    expect(api.getQuote).toHaveBeenCalledWith('quote-a');
    expect(output).toEqual([
      QuoteActions.loadQuotesSuccess({ result: quotePage }),
      QuoteActions.loadQuoteSuccess({ quote: storedQuote }),
    ]);
  });
  it('maps missing quote to a safe detail failure', () => {
    api.getQuote.mockReturnValue(
      throwError(() => new HttpErrorResponse({ status: 404, error: 'owner details' })),
    );
    const output: Action[] = [];
    effects.detail.subscribe((a) => output.push(a));
    actions.next(QuoteActions.loadQuote({ id: 'other-owner' }));
    expect(output).toEqual([QuoteActions.loadQuoteFailure({ message: 'Quote not found.' })]);
  });
  it('cancels all pending requests when authentication changes', () => {
    const pending = new Subject();
    api.createQuote.mockReturnValue(pending);
    api.getQuote.mockReturnValue(pending);
    api.getQuotes.mockReturnValue(pending);
    const output: Action[] = [];
    effects.create.subscribe((a) => output.push(a));
    effects.history.subscribe((a) => output.push(a));
    effects.detail.subscribe((a) => output.push(a));
    actions.next(QuoteActions.createQuote({ request: quoteInput }));
    actions.next(QuoteActions.loadQuote({ id: 'a' }));
    actions.next(QuoteActions.loadQuotes({ page: 0, size: 20 }));
    actions.next(AuthActions.logout());
    pending.next(storedQuote);
    expect(output).toEqual([]);
    expect(pending.observed).toBe(false);
  });
  it('discards superseded detail results', () => {
    const old = new Subject<typeof storedQuote>();
    api.getQuote.mockReturnValueOnce(old);
    const output: Action[] = [];
    effects.detail.subscribe((a) => output.push(a));
    actions.next(QuoteActions.loadQuote({ id: 'old' }));
    actions.next(QuoteActions.loadQuote({ id: 'new' }));
    old.next(storedQuote);
    expect(output).toHaveLength(1);
    expect(old.observed).toBe(false);
  });
});
