# Phase 14 — Audit Consumer Validation

Completed 2026-10-13. Phase 15 is not implemented. No commit created.

## Automated results

| Command | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six modules passed; 322 normal tests, zero failures/errors/skips |
| `./mvnw -B -pl services/audit-service -Ppostgres-it verify` | 4 PostgreSQL integration tests passed |
| `./mvnw -B -pl services/audit-service -Pkafka-it verify` | 1 Kafka/PostgreSQL integration test passed |
| `git diff --check` | Passed |

Audit adds 24 normal mapping/validation cases and preserves its existing startup/health test. Notification code/tests and other application production code remain unchanged. The normal suite is Docker-independent. Integration profiles use disposable PostgreSQL 17.11-bookworm and Kafka 4.1.2.

Normal tests cover event ID, CUSTOMER actor/customer UUID, Quote entity/quote UUID, correlation ID, producer, occurrence versus processing timestamps, exact allowlisted JSON metadata, absent sensitive fields, decimal precision, missing required IDs/producer/time, aggregate mismatch, malformed JSON/data, unsupported type/version and safe persistence failures.

PostgreSQL tests prove exact stored UUID/string/timestamp/JSONB fields, full 128-character correlation preservation, event_id uniqueness, rollback and no processed_events writes. They apply existing V1/V2 plus the necessary V3 correlation-width compatibility migration.

The Kafka test produces before the group starts, then proves earliest consumption and offset 1 after audit persistence. Unknown type advances to 2 by policy. Duplicate event ID stops consumption without advancing beyond 2. Removing the conflicting row **only in the disposable test database** and explicitly restarting demonstrates replay to offset 3. Malformed JSON at offset 3 stops again, leaving committed offset 3 while topic end is 4. No application retry/idempotency/DLQ mechanism is introduced.

## Schema compatibility blocker

The original audit_records.correlation_id is VARCHAR(100), while the existing Gateway/Phase 12 contract permits 128 characters. V3 widens only this column to VARCHAR(128), preserving the complete event value. Existing V1/V2 migration files, Flyway rows and checksums are unchanged; normal Flyway execution appends one V3 row. No other schema/constraint changes are made. This is the narrow necessary exception to the phase's no-migration default.

## Live two-consumer-group proof

The user's existing Auth, Pricing, Quote, Notification and Gateway were already running on ports 8081, 8083, 8082, 8084 and 8080. Left them running and unmodified. Captured all relevant SQL fingerprints, Kafka topic descriptions/end offsets and both group states before Audit startup. The audit-service group was new; Notification already had committed history.

Started only task-owned Audit on 8085 using the simple Maven command and existing local defaults. Audit applied V3 and consumed three historical records, including Phase 12/13 temporary records whose Quote/Auth rows were already deleted. No external HTTP/database lookup or manual audit insert occurred.

Registered/logged in one temporary fictional CUSTOMER and created one quote through Gateway. HTTP returned 201 PRICED. Both consumers independently persisted the same Kafka record:

| Evidence | Value |
| --- | --- |
| Quote ID / Kafka key / audit entity_id | `d4b5cde2-277a-4737-890f-5f93d49ee8dc` |
| Shared event ID | `c8516fb3-0d95-4dd7-8741-08b2b98b1ce7` |
| Customer ID / audit actor_id | `40778087-78a4-412a-aefb-cf1850543762` |
| Correlation ID | `phase14-test-9c175cc08e2646edbc98c319ec7b3798` |
| Notification ID | `028ef30b-e0d5-4973-9e00-4b4d083272fa` |
| Audit ID | `26e12dce-cb73-4d87-9e26-092681042e21` |
| Topic partition / record offset | 1 / 0 |
| Premium | 11200.00 THB |

Audit event_type=QuotePriced, actor_type=CUSTOMER, entity_type=Quote, producer=quote-service. occurred_at matched the event instant; created_at was the audit processing instant. JSONB metadata exactly matched the seven allowed quote-context fields. Notification's schema does not store correlation_id; Audit preserved it and the unchanged Notification consumer logs it alongside the shared event ID.

## Independent group offset evidence

After processing, Kafka CLI reported:

| Group | Partition 0 committed/end | Partition 1 committed/end | Partition 2 committed/end | Lag |
| --- | --- | --- | --- | --- |
| notification-service | 2 / 2 | 1 / 1 | 1 / 1 | 0 on all partitions |
| audit-service | 2 / 2 | 1 / 1 | 1 / 1 | 0 on all partitions |

These are separate group offsets, not shared progress. Notification's prior commits did not prevent the new Audit group from reading the historical records, and both received the new record. Numeric assignments are observed local state, not a contract.

Only quote-topic partition 1's end advanced, from 0 to 1, due to the new producer record. Audit reading history did not change any topic end offset. No domain/DLQ topic configuration, partition count or replication factor changed.

## Safety and cleanup

Narrowly removed the Phase 14 temporary account, quote aggregate, notification and audit row. Auth/Quote/Pricing/Notification fingerprints returned to their pre-validation values, including outbox, processed_events and their Flyway histories. Audit processed_events remains untouched, and its original V1/V2 Flyway rows remain identical. The new V3 migration and three historical audit records are intentionally retained.

Kafka retains historical records and the new validation record; neither group's offsets were reset. Stopped only the task-owned Audit process; the user's preexisting five services and shared infrastructure remain running.

## Limits and readiness

Separate DB and Kafka offset commits leave a crash window. Redelivery after a committed audit insert hits UNIQUE(event_id) and is a processing failure, not duplicate-as-success. Unsupported types are deliberately skipped; malformed supported events/version/database failures stop the container and require investigation. HTTP health alone does not prove consumer progress. Actor attribution is based on event data, not independently re-authenticated.

No processed_events workflow, retry/backoff framework, DLQ, Outbox, Audit REST/UI, Notification UI, external logging platform or additional event type was implemented. Phase 14 is complete and ready for Phase 15 — Idempotent Consumers; Phase 15 has not started.
