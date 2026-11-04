import { OperationsStore } from '@app/store/operations/operations.store';
import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { interval } from 'rxjs';

@Component({
  selector: 'app-operations',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './operations.component.html',
  styleUrl: './operations.component.css',
})
export class OperationsComponent {
  private readonly store = inject(OperationsStore);
  readonly state = this.store;
  readonly autoRefresh = signal(true);
  readonly healthy = computed(
    () =>
      this.state.overview()?.services.filter((s) => s.status === 'UP' && s.readiness === 'READY')
        .length ?? 0,
  );
  readonly metricLabels: Record<string, string> = {
    quoteCreated: 'Quotes created',
    quoteFailed: 'Quote failures',
    pricingCallErrors: 'Pricing call errors',
    pricingAvgLatencyMs: 'Pricing avg latency (ms)',
    circuitBreakerState: 'Circuit breaker',
    outboxPending: 'Outbox pending',
    outboxPublished: 'Outbox published',
    outboxFailed: 'Outbox publish failures',
    processedEvents: 'Processed events',
    duplicateEvents: 'Duplicate events',
    dlqCount: 'DLQ acknowledged',
  };
  constructor() {
    this.refresh();
    interval(15000)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.autoRefresh() && !this.state.loading()) this.refresh();
      });
    inject(DestroyRef).onDestroy(() => this.store.leave());
  }
  refresh() {
    if (!this.state.loading()) this.store.refresh();
  }
  entries(metrics: Record<string, number | string | null>) {
    // Render only the fixed presentation keys, never arbitrary downstream fields.
    return Object.keys(this.metricLabels)
      .filter((key) => key in metrics)
      .map((key) => ({ label: this.metricLabels[key], value: metrics[key] }));
  }
  isNumber(value: unknown): value is number {
    return typeof value === 'number' && Number.isFinite(value);
  }
}
