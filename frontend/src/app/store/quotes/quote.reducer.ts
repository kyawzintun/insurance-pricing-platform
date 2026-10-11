import { createFeature, createReducer, on } from '@ngrx/store';
import { AuthActions } from '@app/store/auth/auth.actions';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { Quote, QuotePage } from '@app/core/quotes/quote.models';

type RequestStatus = 'idle' | 'pending' | 'success' | 'error';
export interface QuoteState {
  currentQuote: Quote | null;
  selectedQuote: Quote | null;
  history: QuotePage;
  createStatus: RequestStatus;
  historyStatus: RequestStatus;
  detailStatus: RequestStatus;
  createError: string | null;
  historyError: string | null;
  detailError: string | null;
}
export const initialQuoteState: QuoteState = {
  currentQuote: null,
  selectedQuote: null,
  history: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
  createStatus: 'idle',
  historyStatus: 'idle',
  detailStatus: 'idle',
  createError: null,
  historyError: null,
  detailError: null,
};
export const quoteFeature = createFeature({
  name: 'quotes',
  reducer: createReducer(
    initialQuoteState,
    on(QuoteActions.createQuote, (state) => ({
      ...state,
      createStatus: 'pending' as const,
      createError: null,
    })),
    on(QuoteActions.createQuoteSuccess, (state, { quote }) => ({
      ...state,
      currentQuote: quote,
      createStatus: 'success' as const,
    })),
    on(QuoteActions.createQuoteFailure, (state, { message }) => ({
      ...state,
      createStatus: 'error' as const,
      createError: message,
    })),
    on(QuoteActions.loadQuotes, (state, { page, size }) => ({
      ...state,
      history: { content: [], page, size, totalElements: 0, totalPages: 0 },
      historyStatus: 'pending' as const,
      historyError: null,
    })),
    on(QuoteActions.loadQuotesSuccess, (state, { result }) => ({
      ...state,
      history: result,
      historyStatus: 'success' as const,
    })),
    on(QuoteActions.loadQuotesFailure, (state, { message }) => ({
      ...state,
      historyStatus: 'error' as const,
      historyError: message,
    })),
    on(QuoteActions.loadQuote, (state) => ({
      ...state,
      selectedQuote: null,
      detailStatus: 'pending' as const,
      detailError: null,
    })),
    on(QuoteActions.loadQuoteSuccess, (state, { quote }) => ({
      ...state,
      selectedQuote: quote,
      detailStatus: 'success' as const,
    })),
    on(QuoteActions.loadQuoteFailure, (state, { message }) => ({
      ...state,
      selectedQuote: null,
      detailStatus: 'error' as const,
      detailError: message,
    })),
    on(
      AuthActions.logout,
      AuthActions.loginFailure,
      AuthActions.loginSuccess,
      () => initialQuoteState,
    ),
  ),
});
