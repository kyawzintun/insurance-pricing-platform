import { QuoteStore } from '@app/store/quotes/quote.store';
import { Component, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';

@Component({
  selector: 'app-quote-detail',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './quote-detail.component.html',
})
export class QuoteDetailComponent {
  private readonly store = inject(QuoteStore);
  readonly quote = this.store.selectedQuote;
  readonly status = this.store.detailStatus;
  readonly error = this.store.detailError;
  readonly showBreakdown = signal(true);
  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        this.showBreakdown.set(true);
        this.store.loadQuote({ id: params.get('id') ?? '' });
      });
  }
}
