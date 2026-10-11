import { inject, Injectable } from '@angular/core';
import { Router } from '@angular/router';
import { Actions, createEffect, ofType } from '@ngrx/effects';
import { catchError, exhaustMap, map, of, switchMap, takeUntil, tap } from 'rxjs';
import { QuoteApiService } from '@app/core/quotes/quote-api.service';
import { quoteErrorMessage } from '@app/core/quotes/quote-errors';
import { AuthActions } from '@app/store/auth/auth.actions';
import { QuoteActions } from '@app/store/quotes/quote.actions';

@Injectable()
export class QuoteEffects {
  private readonly actions = inject(Actions);
  private readonly api = inject(QuoteApiService);
  private readonly router = inject(Router);
  private readonly sessionChanged = this.actions.pipe(
    ofType(AuthActions.logout, AuthActions.loginFailure, AuthActions.loginSuccess),
  );
  readonly create = createEffect(() =>
    this.actions.pipe(
      ofType(QuoteActions.createQuote),
      exhaustMap(({ request }) =>
        this.api.createQuote(request).pipe(
          map((quote) => QuoteActions.createQuoteSuccess({ quote })),
          catchError((error) =>
            of(QuoteActions.createQuoteFailure({ message: quoteErrorMessage(error, 'create') })),
          ),
          takeUntil(this.sessionChanged),
        ),
      ),
    ),
  );
  readonly history = createEffect(() =>
    this.actions.pipe(
      ofType(QuoteActions.loadQuotes),
      switchMap(({ page, size }) =>
        this.api.getQuotes(page, size).pipe(
          map((result) => QuoteActions.loadQuotesSuccess({ result })),
          catchError((error) =>
            of(QuoteActions.loadQuotesFailure({ message: quoteErrorMessage(error, 'history') })),
          ),
          takeUntil(this.sessionChanged),
        ),
      ),
    ),
  );
  readonly detail = createEffect(() =>
    this.actions.pipe(
      ofType(QuoteActions.loadQuote),
      switchMap(({ id }) =>
        this.api.getQuote(id).pipe(
          map((quote) => QuoteActions.loadQuoteSuccess({ quote })),
          catchError((error) =>
            of(QuoteActions.loadQuoteFailure({ message: quoteErrorMessage(error, 'detail') })),
          ),
          takeUntil(this.sessionChanged),
        ),
      ),
    ),
  );
  readonly afterCreate = createEffect(
    () =>
      this.actions.pipe(
        ofType(QuoteActions.createQuoteSuccess),
        tap(({ quote }) => {
          void this.router.navigate(['/quotes', quote.id]);
        }),
      ),
    { dispatch: false },
  );
}
