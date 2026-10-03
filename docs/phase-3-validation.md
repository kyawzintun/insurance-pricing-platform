# Phase 3 Validation

Status: Complete, validated on 2026-10-03. Phase 4 — Authentication Basics has not started.

## Migration Inventory

All migrations are service-owned; no files were added under `infrastructure/`.

### auth-service

- [V1__create_users.sql](../services/auth-service/src/main/resources/db/migration/V1__create_users.sql)
- [V2__create_refresh_tokens.sql](../services/auth-service/src/main/resources/db/migration/V2__create_refresh_tokens.sql)
- [V3__create_outbox_events.sql](../services/auth-service/src/main/resources/db/migration/V3__create_outbox_events.sql)

### quote-service

- [V1__create_vehicle_reference_tables.sql](../services/quote-service/src/main/resources/db/migration/V1__create_vehicle_reference_tables.sql)
- [V2__create_quotes.sql](../services/quote-service/src/main/resources/db/migration/V2__create_quotes.sql)
- [V3__create_pricing_snapshot_tables.sql](../services/quote-service/src/main/resources/db/migration/V3__create_pricing_snapshot_tables.sql)
- [V4__create_outbox_events.sql](../services/quote-service/src/main/resources/db/migration/V4__create_outbox_events.sql)
- [V5__seed_vehicle_data.sql](../services/quote-service/src/main/resources/db/migration/V5__seed_vehicle_data.sql)

### pricing-service

- [V1__create_pricing_rules.sql](../services/pricing-service/src/main/resources/db/migration/V1__create_pricing_rules.sql)
- [V2__create_outbox_events.sql](../services/pricing-service/src/main/resources/db/migration/V2__create_outbox_events.sql)
- [V3__seed_pricing_rules.sql](../services/pricing-service/src/main/resources/db/migration/V3__seed_pricing_rules.sql)

### notification-service

- [V1__create_notifications.sql](../services/notification-service/src/main/resources/db/migration/V1__create_notifications.sql)
- [V2__create_processed_events.sql](../services/notification-service/src/main/resources/db/migration/V2__create_processed_events.sql)

### audit-service

- [V1__create_audit_records.sql](../services/audit-service/src/main/resources/db/migration/V1__create_audit_records.sql)
- [V2__create_processed_events.sql](../services/audit-service/src/main/resources/db/migration/V2__create_processed_events.sql)

## Results

| Validation | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six modules built; six context/HTTP tests passed, zero failures/errors. |
| Five database-service JARs started with `--spring.profiles.active=local` | All five healthy; Flyway applied all 15 migrations successfully. |
| Auth Flyway history | V1–V3 applied, all successful. |
| Quote Flyway history | V1–V5 applied, all successful. |
| Pricing Flyway history | V1–V3 applied, all successful. |
| Notification Flyway history | V1–V2 applied, all successful. |
| Audit Flyway history | V1–V2 applied, all successful. |
| Second startup of all five services | All healthy; Flyway validated checksums and reported schemas up to date. No migrations reapplied. |
| Exact PostgreSQL table inventory | Auth 3, Quote 8, Pricing 2, Notification 2, Audit 2 application tables, plus one Flyway history table per database; no unexpected tables. |
| Catalog seeds | Four active brands, twelve active models; exact requested names verified. |
| Pricing seeds | Seven enabled rules, version 0, effective from 2026-01-01 UTC; requested operators, comparisons, factors and base amount verified. |
| Restart stability | Migration versions, scripts, checksums, success flags and seed rows unchanged. |
| Foreign keys | One Auth FK and five Quote FKs, all between service-owned tables; none in other databases. No cross-service FK references. |
| Constraint smoke check | Negative driving experience, missing parent quote, and duplicate per-quote driver all rejected with expected CHECK/FK/UNIQUE violations. Temporary test rows rolled back; quotes and drivers remained empty. |
| Kafka safety | Full topic list, domain topic metadata/offsets, and consumer-group list unchanged across both startup cycles. No application messaging code added. |
| Scope check | Gateway remains database-free; no Java, POM, runtime configuration, Docker, entities, repositories, APIs, or routing changes. |

The services used exported existing local environment settings and separate validation ports 18081–18085, without changing committed defaults. Validation processes were stopped after both cycles. Shared infrastructure and migrated data remain available. No reset, volume deletion, repair, or baseline operation was needed. No migration errors were found.

## Commands and Database Queries

After exporting the trusted local `.env` as documented in the services README:

```bash
./mvnw -B clean verify
docker compose up -d
# Each database service was run separately; example:
AUTH_SERVICE_PORT=18081 java -Xmx256m -jar services/auth-service/target/auth-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
curl --fail http://localhost:18081/actuator/health
```

Queried each database with the PostgreSQL container's `psql -v ON_ERROR_STOP=1`:

```sql
SELECT tablename FROM pg_tables
WHERE schemaname NOT IN ('pg_catalog', 'information_schema');
SELECT version, script, checksum, success
FROM flyway_schema_history ORDER BY installed_rank;
SELECT conrelid::regclass, confrelid::regclass
FROM pg_constraint WHERE contype = 'f';
```

Joined Quote brands/models and selected Pricing rule operands/amounts to verify exact seed contents. All non-seeded application tables remained empty. Representative invalid inserts were checked inside a transaction that ended with ROLLBACK.

Kafka CLI `kafka-topics.sh --list`, `kafka-topics.sh --describe --topic 'insurance.*'`, `kafka-get-offsets.sh --topic 'insurance.*'`, and `kafka-consumer-groups.sh --list` were captured before/after using `--bootstrap-server kafka:19092` inside the broker container.

## Assumptions and Deferred Work

See [database design](database-design.md) for constraints/index choices, case-sensitive email uniqueness, snapshot references, stable seeds, and the base-premium operator placeholder. Amounts are educational, not actuarial pricing. No user accounts or passwords are seeded.

The existing context tests exclude persistence, so their success alone does not prove migration correctness; the live PostgreSQL/Flyway runs supplied that validation. No Testcontainers integration suite was introduced.

Authentication, entities, repositories, APIs, pricing/quote behavior, Kafka messaging, outbox publishing, idempotent processing, retries, DLQs, Angular, and gateway routes remain deferred. Phase 3 is ready for Phase 4; no Phase 4 work or Git commit was performed.
