# Phase 27 — PostgreSQL Performance Validation

Validated 2026-11-04 (Asia/Bangkok). This performance deliverable coexists with the repository's already implemented Phase 27 Centralized Operations Dashboard. Its specification/report and frontend remain intact. No next phase was started.

## Method and reproducibility

Run `python3 infrastructure/validation/phase27-postgres-performance.py --output /tmp/phase27-performance.json` with Docker available. PostgreSQL 17.11-bookworm runs in one uniquely named, disposable container, with four isolated service databases, no host port, no shared volumes, and unconditional cleanup. The harness applies service SQL in numeric migration order using psql with ON_ERROR_STOP. The separate postgres-it suite validates actual Flyway startup/checksums and application mappings.

Baseline boundaries: Audit V3, Quote V6, Pricing V4, Auth V4. All baseline plans were measured before adding the new migrations. The final reproducible run reapplies these boundaries, seeds, runs ANALYZE, records every baseline, then applies only the new migrations, runs ANALYZE again and compares. No production fixtures or SQL logging settings changed.

Data: 100,000 audit records; 50,000 quotes across 1,000 customers; 15,000 users (14,850 CUSTOMER); 5,000 historical base rules plus seven existing pricing seeds; each of Auth/Quote/Pricing has 100,000 outbox rows (99,900 PUBLISHED, 100 PENDING). Ten-row timestamp ties exercise UUID ordering. Audit correlation groups have three events; entities have 100 records. UUID entity filters use the same canonical representation as the UI. Outbox payloads contain a synthetic 300-character body. These are deterministic synthetic plan fixtures, not production traffic or complete event envelopes. Quote snapshots are tested through the existing aggregate integration suite rather than bulk-seeded here.

Every plan uses `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` with ordinary planner settings. SQL uses equivalent literal bindings/projections for the application queries; actual parameterized Hibernate Pricing and IgnoreCase SQL is preserved in [SQL capture](phase-27-postgres-sql-capture.txt). Pricing admin's non-null filters simplify its optional-parameter predicates. JDBC expiration/outbox SQL matches the production predicates/order/limit, including FOR UPDATE SKIP LOCKED. No planner scan types are forced.

The harness compares every returned field and ordered row before/after (active pricing is unordered and compared as a set). It asserts all four new indexes appear without Seq Scan on their target paths. Evidence retains row counts/checksums, full plan trees, planning/execution times, rows removed, buffer hits/reads, sorts, indexes and storage sizes in [raw evidence](phase-27-postgres-performance-evidence.json). Timing is one local run per query, not a benchmark distribution or an SLA. Cache warmth, instrumentation and host contention vary; structural reductions justify changes, not timing alone. Autocommit releases the expiration locks after each statement; concurrency/ownership/publishing semantics are validated separately by existing integration tests.

## Measured plans

Times in milliseconds; buffers are root shared hits/reads, including descendants (do not sum them again).

| Query | Before ms | After ms | Before buffers | After buffers | Before plan | After plan |
| --- | ---: | ---: | --- | --- | --- | --- |
| audit.count | 19.382 | 26.684 | 2858/0 | 2226/632 | Aggregate → Seq Scan | Aggregate → Seq Scan |
| audit.latest | 0.099 | 0.088 | 16/0 | 14/2 | Limit → Incremental Sort → Index Scan idx_audit_records_occurred_at | Limit → Incremental Sort → Index Scan idx_audit_records_occurred_at |
| audit.correlation | 12.785 | 0.109 | 2864/0 | 7/3 | Limit → Sort → Seq Scan | Limit → Sort → Bitmap Heap Scan → Bitmap Index Scan idx_audit_records_correlation_id |
| audit.entity | 0.743 | 0.553 | 108/0 | 106/2 | Limit → Sort → Index Scan idx_audit_records_entity | Limit → Sort → Index Scan idx_audit_records_entity |
| audit.event | 0.094 | 0.114 | 17/0 | 17/0 | Limit → Incremental Sort → Index Scan idx_audit_records_occurred_at | Limit → Incremental Sort → Index Scan idx_audit_records_occurred_at |
| quote.count | 9.176 | 8.614 | 820/0 | 820/0 | Aggregate → Seq Scan | Aggregate → Seq Scan |
| quote.customer_count | 0.293 | 0.289 | 53/0 | 50/3 | Aggregate → Bitmap Heap Scan → Bitmap Index Scan idx_quotes_customer_created_at | Aggregate → Bitmap Heap Scan → Bitmap Index Scan idx_quotes_customer_created_at |
| quote.customer | 0.184 | 0.175 | 33/0 | 33/0 | Limit → Incremental Sort → Index Scan idx_quotes_customer_created_at | Limit → Incremental Sort → Index Scan idx_quotes_customer_created_at |
| quote.admin | 17.95 | 0.101 | 826/0 | 1/3 | Limit → Sort → Seq Scan | Limit → Index Only Scan idx_quotes_created_id |
| quote.deep_admin | 28.478 | 10.069 | 826/0 | 2826/197 | Limit → Sort → Seq Scan | Limit → Index Only Scan idx_quotes_created_id |
| quote.expiration | 0.241 | 0.322 | 114/0 | 112/2 | Limit → LockRows → Incremental Sort → Index Scan idx_quotes_status_expires_at | Limit → LockRows → Incremental Sort → Index Scan idx_quotes_status_expires_at |
| quote.outbox | 0.254 | 0.573 | 17/0 | 9/8 | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at |
| pricing.active | 0.94 | 1.076 | 87/0 | 16/71 | Seq Scan | Seq Scan |
| pricing.admin | 0.104 | 0.111 | 23/0 | 21/2 | Limit → Index Scan pricing_rules_pkey | Limit → Index Scan pricing_rules_pkey |
| pricing.outbox | 0.269 | 0.284 | 17/0 | 16/1 | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at |
| auth.customer_count | 3.271 | 3.211 | 334/0 | 334/0 | Aggregate → Seq Scan | Aggregate → Seq Scan |
| auth.email | 6.585 | 0.06 | 334/0 | 1/2 | Seq Scan | Index Scan idx_users_upper_email |
| auth.customers | 6.325 | 0.07 | 340/0 | 4/3 | Limit → Sort → Seq Scan | Limit → Index Scan idx_users_role_created_id |
| auth.outbox | 0.261 | 0.387 | 17/0 | 17/0 | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at | Limit → Incremental Sort → Index Scan idx_outbox_events_status_created_at |

## Decisions and write costs

- **Audit V4** adds only `idx_audit_records_correlation_id(correlation_id)`. Baseline discarded 99,997 rows to find three. The small indexed result still sorts three rows. One additional B-tree write/storage entry per audit insert is justified by exact trace investigation. A larger correlation/time/UUID index is unnecessary for this result cardinality.
- **Quote V7** adds `idx_quotes_created_id(created_at DESC, id DESC)`. Baseline global history scanned all 50,000 rows and top-N sorted; offset 40,000 spilled an external merge sort. The new ordered index serves global ID pagination and removes sorting. One extra entry per quote insert and relevant update; the existing customer-leading index cannot replace it.
- **Auth V5** adds `idx_users_upper_email(upper(email))` and `idx_users_role_created_id(role, created_at DESC, id DESC)`. IgnoreCase emits `upper(email)=upper(?)`; ordinary case-sensitive email uniqueness does not cover that expression. The new expression index is deliberately nonunique, preserving existing identity/constraint behavior. Baseline discarded 14,999 users for email and scanned 14,850 customers before sorting. Costs are two more index entries per registration, maintenance for email/role/order changes and extra storage. Neither duplicates plain email uniqueness or the UUID primary key.
- **Retained Audit indexes:** latest page uses occurred_at with a tiny incremental sort; event filter also stops early; entity index retrieves 100 records before top-N sorting. No additional actor, producer, event-ID, event/time/UUID or entity/time/UUID indexes. Unique event_id already supports exact event lookup. Other combinations are possible but are not an observed bottleneck here.
- **Retained Quote customer/expiration indexes:** customer index reads only a small prefix with incremental tie sorting; expiration uses status/expiry Index Scan, incremental sort, LockRows, LIMIT 100. No replacement or duplicate UUID-extended indexes. Both ownership clauses remain in both ID and snapshot queries; scheduler transaction, SKIP LOCKED and outbox atomicity are unchanged.
- **Retained all three outbox indexes:** status/created_at reads only the 100 pending records, followed by a small incremental sort. A partial PENDING index was considered but not added: the existing scan touches few buffers and there is no meaningful scan bottleneck to fix. Thus no write/storage churn or predicate/planner risk is introduced. Session advisory locks, autocommit send interval, retry updates, batch ordering and acknowledgment semantics remain intact.
- **Pricing:** active query intentionally returns 3,757 of 5,007 rules. Sequential scanning is appropriate at this selectivity; the engine validates all active rules, sorts newest effective first with UUID-text ties, then chooses matching rules. No LIMIT rewrite or new enabled/effective index. Admin ID-ordered filtered page uses the primary key and stops early. Distribution is deliberately broad; a different disabled/future/category distribution needs fresh measurement.
- **processed_events / Notification:** only atomic insert ON CONFLICT(event_id, consumer_name), with no production processed_at/consumer_name operational scan. Existing composite primary keys are sufficient. No schema change.

New index sizes on this fresh fixture (bytes; production bloat/distribution will differ):

```text
audit
idx_audit_records_correlation_id|1974272
```

```text
quote
idx_quotes_created_id|2056192
```

```text
auth
idx_users_role_created_id|892928
idx_users_upper_email|761856
```

## Query counts and pagination

QuoteRetrievalService pages scalar IDs, counts, bulk-fetches snapshots with an entity graph and restores ID-page order. Existing PostgreSQL assertions verify at most three SELECTs for CUSTOMER/ADMIN pages and one for detail, including multiple adjustments. Audit list/count and detail operate on scalar AuditRecord data, including JSON metadata; customer list/count maps scalar User fields. Neither introduces per-row relationship queries. No N+1 found; no repository or business-code rewrite was needed.

Exact counts remain O(matching rows): global Audit/Quote counts still scan the table; customer-scoped Quote count uses its existing index. Page improvements do not make exact totals constant time. Offset 40,000 still visits 40,020 entries even with the new ordered index, so deep pagination remains linear. Cursor pagination, count-contract changes and retention are future decisions, not this phase.

## Verification

- Full `./mvnw -B verify`: PASS, 456 tests (all six modules).
- `./mvnw -B -Ppostgres-it -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false -Dspring.jpa.show-sql=true verify`: PASS, 57 PostgreSQL integration tests, real Flyway and application behavior. SQL flag is validation-only.
- Disposable performance harness: PASS, all result comparisons and four structural index assertions.
- Initial sandbox Maven invocation could not bind HTTP sockets; unrestricted retry passed. Initial postgres-it caught Quote's migration-count assertion (6 versus new 7); updated assertion and reran the complete profile successfully. Harness startup was corrected to wait for TCP readiness rather than the temporary initialization server. Failed developmental runs are not counted as evidence.
- Original Flyway migrations untouched; only Audit V4, Auth V5, Quote V7 added. No frontend, Pricing/Notification schema, security/event or production logging change.

## Operational limits

Standard transactional CREATE INDEX is suitable for this local learning deployment; it can block writes. A production rollout needs an assessed lock window or separately configured nontransactional concurrent-index strategy. Normal autovacuum/ANALYZE remains important as outboxes transition and tables grow; no global vacuum, pool or server setting was tuned. Fresh inserted fixtures are not a model of long-running bloat or cold-disk behavior. Phase 26 cached outbox gauges and HTTP/DB metrics remain available; this phase isolates query-plan work rather than claiming HTTP throughput gains from SQL timings.

Phase 27 PostgreSQL Performance is complete and ready for final portfolio polish, with the measured workload and limits above.

## File inventory

Created:

- `infrastructure/validation/phase27-postgres-performance.py`
- `services/audit-service/src/main/resources/db/migration/V4__index_audit_correlation_lookup.sql`
- `services/quote-service/src/main/resources/db/migration/V7__index_admin_quote_history.sql`
- `services/auth-service/src/main/resources/db/migration/V5__index_customer_history_and_email_lookup.sql`
- `docs/specs/phase-27-postgres-performance.md`
- `docs/phase-27-postgres-performance-validation.md`
- `docs/phase-27-postgres-performance-evidence.json`
- `docs/phase-27-postgres-sql-capture.txt`

Modified:

- `services/quote-service/src/test/java/com/insurance/platform/quote/QuotePostgresIT.java` (migration count only)
- Audit, Quote and Auth service READMEs
- `docs/database-design.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/specs/README.md`
