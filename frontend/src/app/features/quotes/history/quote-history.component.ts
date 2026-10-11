import { Component, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { quoteFeature } from '@app/store/quotes/quote.reducer';

@Component({
  selector: 'app-quote-history',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './quote-history.component.html',
})
export class QuoteHistoryComponent {
  private readonly store = inject(Store);
  readonly history = this.store.selectSignal(quoteFeature.selectHistory);
  readonly status = this.store.selectSignal(quoteFeature.selectHistoryStatus);
  readonly error = this.store.selectSignal(quoteFeature.selectHistoryError);

  constructor() {
    this.load(this.history().page);
  }
  load(page: number): void {
    if (page < 0) return;
    this.store.dispatch(QuoteActions.loadQuotes({ page, size: this.history().size }));
  }
}
