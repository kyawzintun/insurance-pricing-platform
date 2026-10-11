import { createAction, props } from '@ngrx/store';
import { CreateQuoteRequest, Quote, QuotePage } from '@app/core/quotes/quote.models';

// Explicit action names keep the mapping between declaration and dispatch visible.
export const QuoteActions = {
  createQuote: createAction('[Quotes] Create', props<{ request: CreateQuoteRequest }>()),
  createQuoteSuccess: createAction('[Quotes] Create Success', props<{ quote: Quote }>()),
  createQuoteFailure: createAction('[Quotes] Create Failure', props<{ message: string }>()),
  loadQuotes: createAction('[Quotes] Load History', props<{ page: number; size: number }>()),
  loadQuotesSuccess: createAction('[Quotes] Load History Success', props<{ result: QuotePage }>()),
  loadQuotesFailure: createAction('[Quotes] Load History Failure', props<{ message: string }>()),
  loadQuote: createAction('[Quotes] Load Detail', props<{ id: string }>()),
  loadQuoteSuccess: createAction('[Quotes] Load Detail Success', props<{ quote: Quote }>()),
  loadQuoteFailure: createAction('[Quotes] Load Detail Failure', props<{ message: string }>()),
};
