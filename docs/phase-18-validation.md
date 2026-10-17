# Phase 18 — Quote Expiration validation

Date: 2026-10-17 (Asia/Bangkok). **Phase 18 complete and ready for Phase 19 —
Auth/User Events. Phase 19 has not started.**

## Implementation

See [specification](specs/phase-18-quote-expiration.md) and
[event contract](quote-expired-event.md). Defaults are enabled, 60000 ms fixed
poll delay, and batch size 50 (1–500). Tests can disable the scheduler using
`quote.expiration.enabled=false` independently of outbox publishing.

The query locks at most the configured batch using PRICED + expires_at <= UTC now,
ordered by expiry and UUID, with FOR UPDATE SKIP LOCKED. Only status/updated_at
change. The existing expiry index is reused. One bounded Spring transaction
contains JDBC updates and JPA outbox inserts on the same datasource. Rollback
leaves no event intent or status change. Concurrent workers skip locked rows;
repeated polling skips EXPIRED rows. No in-memory concurrency guard is used.

QuoteExpired v1 uses a fresh event UUID and a fresh per-expiration UUID correlation
ID shared between envelope/outbox. Aggregate type Quote, aggregate ID/key quoteId,
producer quote-service, topic insurance.quote.events. Event occurrence and
expiredAt equal processing time; originalExpiresAt retains the stored expiry.
The existing publisher receives the immutable envelope through a PENDING row
with retry_count=0 and null published_at. Its publication/retry/locking design
is unchanged. No Kafka API is used by the expiration service/scheduler.

Notification maps to CREATED / QUOTE_EXPIRED and a simple expired message. Audit
maps to QuoteExpired / SYSTEM / null actor_id, with compact expiration metadata.
Both reuse eventId + consumerName markers and the existing retry/DLQ behavior.
Unknown types retain intentional skipping. QuotePriced v1 wire output is unchanged.

## Automated results

| Validation | Result |
| --- | --- |
| `./mvnw -q clean verify` | All six modules passed; 366 normal tests, zero failures/errors/skips |
| Quote `-Ppostgres-it verify` | 20 passed, including 3 new expiration tests |
| Quote `-Pkafka-it verify` | Comprehensive producer lifecycle test passed with added expiration checks |
| Notification/Audit `-Pkafka-it verify` | Both comprehensive lifecycle tests passed with added QuoteExpired checks |
| Notification/Audit `-Ppostgres-it verify` | 11 Notification + 12 Audit tests passed |
| `git diff --check` | Passed |

New normal tests cover disabled/configured scheduling, registered scheduled task,
batch validation, failure retry on the next poll, and both consumer mappings and
duplicate markers. New PostgreSQL checks cover future, overdue, exact-boundary,
EXPIRED and FAILED quotes; ordered size-one batching; stored history and all
snapshot tables; event envelope, correlation, pending state; no Pricing/Kafka
calls; outer rollback; actual PostgreSQL failures on each write; and two concurrent
workers with a latch holding the first transaction open. The second worker returns
zero without waiting, then only one intent exists after commit and repeated poll.

Quote Kafka validation starts from a normally priced quote, makes only its expiry
due, calls expiration, verifies PENDING, and uses the existing publisher to reach
PUBLISHED. Broker records have the same quoteId key and partition as QuotePriced,
a later (not necessarily consecutive) offset, correct v1 envelope and premium.
Another expiration/publisher cycle emits nothing.

Both consumer Kafka matrices additionally deliver QuoteExpired twice, observe
exactly one new business row/marker, and route an unsupported QuoteExpired version
to the same existing DLQ. Existing transient failures, exhausted retries, failed
DLQ publication and crash/redelivery regressions continue to pass. Real databases
and brokers were disposable Testcontainers resources.

Initial validation encountered sandbox socket restrictions; the suites were rerun
outside the sandbox. During development, a test mock startup interaction and a
fixture variable collision were corrected before successful final runs.

## Disposable live flow

Ran freshly packaged Gateway, Auth, Pricing, Quote, Notification and Audit as six
separate processes against a new disposable PostgreSQL (five separate service
databases) and a new disposable Kafka (standard source/DLQ names, three partitions).
The existing local Compose containers and durable databases were untouched.

Registered a temporary CUSTOMER through Gateway, authenticated, and created a quote
normally. Quote ID: `4d5b2a5f-38cd-4cff-8ea1-246311c41f1a`.
Verified PRICED and both QuotePriced consumer rows. Changed only this temporary
quote's expires_at using a UUID-scoped maintenance UPDATE. The global 30-day rule
was not changed. Used a 2000 ms expiration interval and 200 ms publisher interval
in this disposable environment; production defaults remain unchanged.

Observed the actual scheduler transition through Gateway to EXPIRED, exactly one
QuoteExpired outbox row reaching PUBLISHED, a CREATED QUOTE_EXPIRED notification,
and QuoteExpired SYSTEM audit with null actor ID. Each consumer held two business
rows and two processed_events markers: one each for QuotePriced and QuoteExpired.
Compared the Gateway response immediately before expiration with the response
after expiration: every field except status was identical, including the adjusted
stored expires_at, quote reference, money, coverage, driver/vehicle snapshots and
pricing breakdown. The PostgreSQL test independently compares entire historical
snapshot tables and verifies expired history remains retrievable by the owner.

Waited five more seconds (multiple expiration cycles): still exactly one expiration
intent and two business rows per consumer. Terminated the six temporary processes
and removed only the two temporary containers. A first disposable startup attempt
hit a PostgreSQL readiness race; its resources were removed and the successful run
used a corrected readiness check.

Kafka key/partition/offset and payload inspection is covered by the producer Kafka
integration test; the live flow verified broker acknowledgment and both independently
committed consumer results. No topic/group offset reset, shared topic deletion or
shared DB mutation was performed.

## Safety, limitations and deferrals

No migration or topic was added. Runtime expiration writes only quotes.status,
quotes.updated_at and outbox_events; consumers write their normal business/marker
tables. Snapshot and reference/pricing-rule tables remain unchanged. Auth creates
only the temporary live-validation customer within the disposable database.

A batch failure rolls back the whole bounded batch and the next poll retries.
A persistently invalid row can block its batch until operator repair; no poison-row
quarantine or job framework is added. Outbox delivery remains at-least-once, with
consumer idempotency absorbing repeat delivery. Catch-up capacity is at most one
configured batch per interval. These are intentional operational limits.

No renewal, repricing, cancellation, replacement, provider messaging, frontend
workflow, auth/user event, pricing-rule event, outbox cleanup, DLQ replay or
Phase 19 work is implemented.

## Files created/modified

- `.env.example`
- `README.md`
- `docs/architecture.md`
- `docs/implementation-roadmap.md`
- `docs/kafka-design.md`
- `docs/phase-18-validation.md`
- `docs/quote-expired-event.md`
- `docs/specs/README.md`
- `docs/specs/phase-18-quote-expiration.md`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/entity/AuditRecord.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuoteEventDecoder.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuotePricedData.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuotePricedEnvelope.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/service/AuditService.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditEventTests.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditFixtures.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditKafkaIT.java`
- `services/notification-service/README.md`
- `services/notification-service/src/main/java/com/insurance/platform/notification/entity/Notification.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuoteEventDecoder.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuotePricedData.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuotePricedEnvelope.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/service/NotificationService.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationEventTests.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationFixtures.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationKafkaIT.java`
- `services/quote-service/README.md`
- `services/quote-service/src/main/java/com/insurance/platform/quote/entity/OutboxEvent.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/event/QuoteExpiredEventData.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/service/QuoteExpirationScheduler.java`
- `services/quote-service/src/main/java/com/insurance/platform/quote/service/QuoteExpirationService.java`
- `services/quote-service/src/main/resources/application.yml`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteExpirationTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteKafkaIT.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuotePostgresIT.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteRetrievalTests.java`
- `services/quote-service/src/test/java/com/insurance/platform/quote/QuoteServiceApplicationTests.java`
- `services/quote-service/src/test/resources/application-test.yml`
