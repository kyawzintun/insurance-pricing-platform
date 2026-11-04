import { inject } from '@angular/core';
import { patchState, signalStore, withHooks, withMethods, withState } from '@ngrx/signals';
import { rxMethod } from '@ngrx/signals/rxjs-interop';
import { catchError, EMPTY, exhaustMap, pipe, Subject, switchMap, takeUntil, tap } from 'rxjs';
import { SessionResetService } from '@app/store/session-reset.service';
import { Router } from '@angular/router';
import { CreateQuoteRequest, Quote, QuotePage } from '@app/core/quotes/quote.models';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { quoteErrorMessage } from '@app/core/quotes/quote-errors';
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

export const QuoteStore = signalStore(
  { providedIn: 'root' },
  withState(initialQuoteState),
  withMethods(
    (
      store,
      api = inject(QuoteApiService),
      router = inject(Router),
      resets = inject(SessionResetService),
    ) => {
      const cancelled = new Subject<void>();
      const reset = () => {
        cancelled.next();
        patchState(store, initialQuoteState);
      };
      const unregister = resets.register(reset);
      return {
        reset,
        _dispose: () => {
          unregister();
          cancelled.next();
          cancelled.complete();
        },
        createQuote: rxMethod<{ request: CreateQuoteRequest }>(
          pipe(
            exhaustMap(({ request }) => {
              patchState(store, { createStatus: 'pending', createError: null });
              return api.createQuote(request).pipe(
                tap((quote) => {
                  patchState(store, { currentQuote: quote, createStatus: 'success' });
                  void router.navigate(['/quotes', quote.id]);
                }),
                catchError((error) => {
                  patchState(store, {
                    createStatus: 'error',
                    createError: quoteErrorMessage(error, 'create'),
                  });
                  return EMPTY;
                }),
                takeUntil(cancelled),
              );
            }),
          ),
        ),
        loadQuotes: rxMethod<{ page: number; size: number }>(
          pipe(
            switchMap(({ page, size }) => {
              patchState(store, {
                history: { content: [], page, size, totalElements: 0, totalPages: 0 },
                historyStatus: 'pending',
                historyError: null,
              });
              return api.getQuotes(page, size).pipe(
                tap((history) => patchState(store, { history, historyStatus: 'success' })),
                catchError((error) => {
                  patchState(store, {
                    historyStatus: 'error',
                    historyError: quoteErrorMessage(error, 'history'),
                  });
                  return EMPTY;
                }),
                takeUntil(cancelled),
              );
            }),
          ),
        ),
        loadQuote: rxMethod<{ id: string }>(
          pipe(
            switchMap(({ id }) => {
              patchState(store, {
                selectedQuote: null,
                detailStatus: 'pending',
                detailError: null,
              });
              return api.getQuote(id).pipe(
                tap((selectedQuote) =>
                  patchState(store, { selectedQuote, detailStatus: 'success' }),
                ),
                catchError((error) => {
                  patchState(store, {
                    selectedQuote: null,
                    detailStatus: 'error',
                    detailError: quoteErrorMessage(error, 'detail'),
                  });
                  return EMPTY;
                }),
                takeUntil(cancelled),
              );
            }),
          ),
        ),
      };
    },
  ),
  withHooks({ onDestroy: (store) => store._dispose() }),
);
