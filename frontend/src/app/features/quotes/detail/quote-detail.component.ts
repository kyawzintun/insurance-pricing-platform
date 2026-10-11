import { Component, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Store } from '@ngrx/store';
import { QuoteActions } from '@app/store/quotes/quote.actions';
import { quoteFeature } from '@app/store/quotes/quote.reducer';

@Component({
  selector: 'app-quote-detail',
  imports: [CurrencyPipe, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './quote-detail.component.html',
})
export class QuoteDetailComponent {
  private readonly store = inject(Store);
  readonly quote = this.store.selectSignal(quoteFeature.selectSelectedQuote);
  readonly status = this.store.selectSignal(quoteFeature.selectDetailStatus);
  readonly error = this.store.selectSignal(quoteFeature.selectDetailError);
  readonly showBreakdown = signal(true);
  constructor() {
    inject(ActivatedRoute)
      .paramMap.pipe(takeUntilDestroyed())
      .subscribe((params) => {
        this.showBreakdown.set(true);
        this.store.dispatch(QuoteActions.loadQuote({ id: params.get('id') ?? '' }));
      });
  }
}
