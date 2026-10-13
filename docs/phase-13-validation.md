# Phase 13 — Notification Consumer Validation

Completed 2026-10-12. Phase 14 has not started. No commit created.

## Automated results

| Command | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six modules passed; 298 normal tests, zero failures/errors/skips |
| `./mvnw -B -pl services/notification-service -Ppostgres-it verify` | 3 PostgreSQL integration tests passed |
| `./mvnw -B -pl services/notification-service -Pkafka-it verify` | 1 real Kafka/PostgreSQL integration test passed |
| `git diff --check` | Passed |

Notification has 21 new normal test cases plus the preserved startup/health test. The normal build remains Docker-independent. Integration profiles use disposable PostgreSQL 17.11-bookworm and Kafka 4.1.2 separately; no shared infrastructure is changed by tests. Frontend and other service production code are unchanged.

Normal tests cover full notification mapping, fixed Clock timestamps, safe failure logging, missing IDs, aggregate mismatch, invalid/missing premiums, malformed JSON/data, unknown type, unsupported version (including integer overflow input), exact decimal parsing, trailing JSON rejection, and diagnostic key mismatch. No HTTP client or coupling was introduced.

PostgreSQL tests use the existing Flyway migrations and verify exact UUID/string/timestamp fields, premium formatting, one inserted row, duplicate event ID failure, transaction rollback and no processed_events writes. The schema has no dedicated money column: premium is mapped exactly into message text.

Kafka integration verifies:

1. A record published before the group starts is consumed with earliest.
2. The notification row exists and the committed next offset reaches 1.
3. An unsupported type is intentionally skipped, advancing to 2.
4. Redelivery of the original event fails on UNIQUE(event_id); the container stops and the committed offset stays 2.
5. In the disposable test database only, removing the conflicting row and explicitly restarting allows replay and advances to 3. This is test maintenance, not application idempotency or automatic retry.
6. Malformed JSON at offset 3 stops the container; committed offset remains 3 while the topic end is 4.
7. processed_events stays empty throughout.

## Live Quote → Kafka → Notification

The existing Compose Kafka and PostgreSQL were already healthy on host ports 29092/15432. Captured topic descriptions, all domain/DLQ partition end offsets, relevant database table fingerprints and the initial consumer group state. The notification-service group did not exist initially.

Started task-owned Auth, Pricing, Quote, Notification and Gateway on free ports 8081/8083/8082/8084/8080. Used explicit local profile/database/broker overrides. Registered and logged in a temporary fictional CUSTOMER and submitted a Toyota Camry quote through Gateway. HTTP response was 201 PRICED. No notification was manually inserted.

| Evidence | Value |
| --- | --- |
| Quote ID / Kafka key | `736db1bf-c758-4167-868f-8ef15beb68fe` |
| Event ID | `e4bf74e6-ea03-4ac6-8f3e-f95c700ec97b` |
| Customer ID | `dc8c9ea4-f768-49fe-bbe6-db4a6f416e60` |
| Correlation ID | `phase13-test-436eb8ccae494db28cac414eb6c9acb0` |
| Notification ID | `7f16e509-3da9-4a64-a971-b60348f3cec2` |
| Record partition / offset | 2 / 0 |
| Premium | 11200.00 THB |
| Notification type / status | QUOTE_PRICED / CREATED |

The persisted event/quote/customer IDs matched the actual Kafka JSON and HTTP response. Message contained the quote reference and exact premium/currency; created_at and processed_at were equal UTC processing instants. Outbox, processed_events, pricing rules, catalog and Flyway fingerprints remained unchanged.

## Historical consumption and group offsets

Earliest deliberately consumed the historical Phase 12 event `b8e7c600-bbca-4708-ad82-96810a074462` from partition 0 offset 0, creating a notification even though that temporary quote/user was previously deleted. Notification has logical cross-service UUID references and makes no lookup call.

After live processing, CLI evidence for notification-service was:

| Partition | Committed next offset | Topic end offset | Lag |
| --- | --- | --- | --- |
| 0 | 1 | 1 | 0 |
| 1 | unset (`-`) | 0 | unset (`-`) |
| 2 | 1 | 1 | 0 |

All three partitions were assigned to this instance. The empty partition need not have a committed offset yet. The initial validation script assumed every partition had a numeric offset; its assertion was corrected to account for the empty partition, and the same event was rechecked read-only without creating another account or quote.

Only partition 2's topic end advanced (0 → 1), from the new Quote producer record. Consuming history did not change partition 0's topic end. No other domain/DLQ partition changed. No topics, partition counts or replication factors were changed.

## Cleanup and retained state

Stopped all five task-owned application process groups; ports 8080–8084 were verified free. Shared Docker infrastructure was left running.

Narrowly removed this Phase 13 temporary quote aggregate, customer account and matching notification. All captured SQL fingerprints returned to baseline except notification_db.notifications, which intentionally retains the newly consumed historical Phase 12 notification. processed_events remains empty. Kafka retains both the historical event and this Phase 13 event; consumer group offsets remain durable. No topic record was deleted and no group offset was reset.

## Limits and deferred work

Database commit and Kafka offset commit are not atomic. A crash between them can cause redelivery; UNIQUE(event_id) then rejects the duplicate and stops consumption. There is no successful duplicate-recognition workflow yet. Invalid supported records stop the container and require investigation; unsupported types are skipped deliberately. HTTP health alone does not prove consumer progress.

No Audit consumer, idempotency/processed_events, retry/backoff framework, DLQ publishing/consumption, Transactional Outbox, external provider, notification REST/UI, migration or other domain event was added. Phase 13 is complete and ready for Phase 14 — Audit Consumer.
