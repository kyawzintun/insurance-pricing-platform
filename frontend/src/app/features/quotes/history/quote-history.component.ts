import { QuoteStore } from '@app/store/quotes/quote.store';
import { Component, inject } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

@Component({
  selector: 'app-quote-history',
  imports: [CurrencyPipe, DatePipe, RouterLink],
  templateUrl: './quote-history.component.html',
})
export class QuoteHistoryComponent {
  private readonly store = inject(QuoteStore);
  readonly history = this.store.history;
  readonly status = this.store.historyStatus;
  readonly error = this.store.historyError;

  constructor() {
    this.load(this.history().page);
  }
  load(page: number): void {
    if (page < 0) return;
    this.store.loadQuotes({ page, size: this.history().size });
  }
}
