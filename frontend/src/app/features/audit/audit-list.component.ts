import { Store } from '@ngrx/store';
import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { auditFilterKeys, AuditQuery } from '@app/core/audit/audit.models';
import { AuditActions, auditFeature } from '@app/store/audit/audit.store';

@Component({
  selector: 'app-audit-list',
  imports: [ReactiveFormsModule, RouterLink, DatePipe],
  templateUrl: './audit-list.component.html',
})
export class AuditListComponent {
  private readonly store = inject(Store);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly validation = signal('');
  readonly fields = auditFilterKeys;
  readonly size = new FormControl(20, { nonNullable: true });
  readonly state = this.store.selectSignal(auditFeature.selectAuditState);
  readonly labels: Record<string, string> = {
    eventType: 'Event type',
    actorType: 'Actor type',
    actorId: 'Actor ID (UUID)',
    entityType: 'Entity type',
    entityId: 'Entity ID (UUID)',
    correlationId: 'Correlation ID',
    producer: 'Producer',
    eventId: 'Event ID (UUID)',
    from: 'From UTC (inclusive)',
    to: 'To UTC (exclusive)',
  };
  readonly form = new FormGroup(
    Object.fromEntries(
      auditFilterKeys.map((key) => [
        key,
        new FormControl('', {
          nonNullable: true,
          validators: Validators.maxLength(key === 'correlationId' ? 128 : 100),
        }),
      ]),
    ),
  );

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const query: AuditQuery = {
        page: this.integer(params.get('page'), 0, 0, 2147483647),
        size: this.integer(params.get('size'), 20, 1, 100),
      };
      for (const key of this.fields) {
        const value = params.get(key) ?? '';
        this.form.controls[key].setValue(value);
        if (value) query[key] = value;
      }
      this.size.setValue(query.size);
      this.store.dispatch(AuditActions.list({ query }));
    });
  }

  private integer(value: string | null, fallback: number, min: number, max: number) {
    const n = value === null ? fallback : Number(value);
    return Number.isInteger(n) && n >= min && n <= max ? n : fallback;
  }

  apply(): void {
    this.validation.set('');
    const filters = Object.fromEntries(
      Object.entries(this.form.getRawValue()).map(([k, v]) => [k, v.trim()]),
    );
    const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
    if (
      this.form.invalid ||
      ['actorId', 'entityId', 'eventId'].some((k) => filters[k] && !uuid.test(filters[k]))
    ) {
      this.validation.set('Check filter lengths and use complete UUIDs for IDs.');
      return;
    }
    for (const k of ['from', 'to']) {
      if (
        filters[k] &&
        (!/^\d{4}-\d{2}-\d{2}T.*Z$/.test(filters[k]) || !Number.isFinite(Date.parse(filters[k])))
      ) {
        this.validation.set('Use UTC timestamps such as 2026-10-20T00:00:00Z.');
        return;
      }
    }
    if (
      filters['from'] &&
      filters['to'] &&
      Date.parse(filters['from']) > Date.parse(filters['to'])
    ) {
      this.validation.set('From must be before or equal to To.');
      return;
    }
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { ...filters, page: 0, size: this.size.value },
    });
  }

  clear(): void {
    this.validation.set('');
    this.form.reset();
    this.size.setValue(20);
    void this.router.navigate([], { relativeTo: this.route, queryParams: { page: 0, size: 20 } });
  }

  retry(): void {
    this.store.dispatch(AuditActions.list({ query: this.state().query }));
  }

  page(page: number): void {
    if (page < 0) return;
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { ...this.state().query, page },
    });
  }
}
