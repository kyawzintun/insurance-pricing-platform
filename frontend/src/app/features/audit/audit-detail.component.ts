import { Store } from '@ngrx/store';
import { DatePipe, JsonPipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Component, computed, inject, signal } from '@angular/core';
import { AuditActions, auditFeature } from '@app/store/audit/audit.store';

@Component({
  selector: 'app-audit-detail',
  imports: [RouterLink, DatePipe, JsonPipe],
  templateUrl: './audit-detail.component.html',
})
export class AuditDetailComponent {
  private readonly store = inject(Store);
  readonly route = inject(ActivatedRoute);
  readonly state = this.store.selectSignal(auditFeature.selectAuditState);
  readonly metadataExpanded = signal(true);
  readonly metadataEmpty = computed(() => {
    const m = this.state().detail?.metadata;
    return m == null || (typeof m === 'object' && Object.keys(m).length === 0);
  });

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.store.dispatch(AuditActions.detail({ id: params.get('id') ?? '' }));
    });
  }
}
