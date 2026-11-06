# Phase 27 — PostgreSQL Performance

Status: implemented and validated on 2026-11-04. Scoped separately from the existing Phase 27 centralized-operations deliverable; no next phase work.

## Contract

Measure existing SQL against disposable PostgreSQL before changing indexes. Preserve API page/size, exact totals, ownership, security, rule validation/selection, UUID ordering, expiration locks, outbox advisory sessions and consumer deduplication. No production seeds, global SQL logging, caching, frontend changes or database server/pool tuning.

## Selected changes

| Service | Append-only migration | Indexes |
| --- | --- | --- |
| Audit | V4__index_audit_correlation_lookup.sql | correlation_id |
| Quote | V7__index_admin_quote_history.sql | created_at DESC, id DESC |
| Auth | V5__index_customer_history_and_email_lookup.sql | upper(email); role, created_at DESC, id DESC |

The expression index is nonunique; existing email uniqueness remains authoritative. Pricing, Notification, processed_events, existing outbox indexes, customer quote and expiration indexes remain unchanged because measurements do not justify additional indexes.

## Acceptance and reproduction

Run `./mvnw -B verify`, then `./mvnw -B -Ppostgres-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false verify` and `python3 infrastructure/validation/phase27-postgres-performance.py --output /tmp/phase27-performance.json`.

The performance harness needs Python 3 and Docker, applies the owning service SQL migrations to private disposable databases, seeds representative volumes, ANALYZEs, captures baseline and optimized EXPLAIN ANALYZE/BUFFERS plans, asserts result equivalence and target index use, and removes its container. Large fixtures never enter normal tests or Flyway migrations. PostgreSQL profiles additionally exercise actual Flyway.

See [validation and tradeoffs](../phase-27-postgres-performance-validation.md), [full plans](../phase-27-postgres-performance-evidence.json), and [actual Hibernate SQL](../phase-27-postgres-sql-capture.txt). Timings are reported evidence, never hard pass/fail thresholds. Production concurrent-index rollout, cursor pagination, count-contract redesign, retention and long-running bloat analysis remain future work.
