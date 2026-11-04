import { AuditStore } from '@app/store/audit/audit.store';
import { DatePipe, JsonPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, computed, inject, signal } from '@angular/core';

@Component({
  selector: 'app-audit-detail',
  imports: [RouterLink, DatePipe, JsonPipe],
  templateUrl: './audit-detail.component.html',
})
export class AuditDetailComponent {
  private readonly store = inject(AuditStore);
  readonly route = inject(ActivatedRoute);
  readonly state = this.store;
  readonly metadataExpanded = signal(true);
  readonly metadataEmpty = computed(() => {
    const m = this.state.detail()?.metadata;
    return m == null || (typeof m === 'object' && Object.keys(m).length === 0);
  });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.store.loadDetail({ id: params.get('id') ?? '' });
    });
  }
}
