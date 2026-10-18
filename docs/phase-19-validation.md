# Phase 19 — Auth/User Events validation

Validated 2026-10-17 (Asia/Bangkok). Phase 19 only. No Phase 20 implementation.

## Contract and implementation

1. **Files:** complete created/modified manifest below. Primary production changes
   are Auth; Notification/Audit receive focused user-event compatibility additions.
   Quote, Pricing, Gateway routing and frontend production code are unchanged.
2. **Contract:** UserRegistered v1, User aggregate, aggregateId=userId,
   producer=auth-service. Common nine-field envelope; data is userId, canonical
   email, CUSTOMER role, ACTIVE status, registeredAt. See [contract](user-registered-event.md).
3. **Transaction:** AuthService.register remains transactional. User flush and
   immutable outbox saveAndFlush join that same PostgreSQL transaction. Outbox
   failure/outer rollback removes both writes. No direct Kafka send occurs in registration.
4. **Correlation/time:** valid Gateway correlation IDs up to 128 characters survive
   in envelope/row; safe UUID fallback for direct callers. One injected UTC Clock
   instant, truncated to microseconds, supplies user creation and event times.
5. **Privacy:** explicit data allowlist excludes password/hash/token/Authorization,
   JWT key, names and login metadata. Email exists only as canonical event data;
   Kafka key is userId, no producer headers. Audit omits email. Application logs
   from disposable Gateway/Auth/Notification/Audit were scanned without exposing
   temporary emails, test password, BCrypt hash or token fields.
6. **Auth outbox:** existing table, immutable event identity/JSON; initial PENDING,
   retry_count=0, published_at=null. UserRegistered applies only to new registrations.
7. **Publisher:** configurable fixed-delay bounded batch (1–500), ordered by
   created_at/id. Session advisory lock `(741019, 1)` serializes Auth instances.
   Autocommit statements keep transactions closed during network waits. Kafka
   acks=all precedes PUBLISHED/published_at; failed sends increment retry_count.
8. **Delivery:** at-least-once. Send success followed by failed status update can
   resend identical eventId/key/JSON/correlation/time. Consumers absorb duplicates.
9. **Notification:** USER_REGISTERED / CREATED, welcome text, customer_id=userId,
   quote_id=null. No email/SMS. Existing schema already permits null quote_id.
10. **Audit:** UserRegistered, CUSTOMER actor/userId, User entity/userId, envelope
    correlation/occurredAt/producer. Metadata contains only role/status.
11. **Retry/DLQ:** existing Phase 17 error handler/recovery format, fixed configurable
    backoff and bounded attempts. Invalid supported input/version goes directly
    to user DLQ. Exact key/value bytes, source partition and allowlisted diagnostic
    headers survive. DLQ acknowledgment gates source completion; failed recovery
    leaves source replayable. Unknown valid types continue to skip successfully.
12. **Migration:** Auth V4 widens outbox correlation_id from 100 to 128; prior
    migrations/checksums untouched. No consumer migration, table or topic addition.

## Executed tests

13. **Normal tests:** producer envelope/canonical email/default role/status, one
    occurrence time, safe payload, correlation propagation/fallback, configurable
    scheduler, batch validation, acknowledgment ordering, send/status failures,
    retry bytes/identity, empty/contended batches and safe publication logs.
    Both consumer decoders cover valid/unknown types and invalid supported input.
14. **Auth PostgreSQL:** 4 passed: user+intent persistence and duplicate rejection;
    forced outbox insert failure rolls back user; outer rollback removes both;
    ordered bounded polling, session-lock exclusion, retry count, PUBLISHED update
    and immutable JSON. Real BCrypt remains cost 12 and matches the input password.
15. **Auth Kafka:** 1 passed with disposable PostgreSQL + Kafka. Broker paused
    before registration: user+PENDING intent commit; failed send remains PENDING;
    broker recovery publishes original stored bytes/key and marks PUBLISHED;
    a later poll ignores PUBLISHED work. Ambiguous timeout duplicates are valid.
16. **Consumers:** Notification PostgreSQL 11 passed; Audit PostgreSQL 12 passed.
    Notification Kafka 2 passed; Audit Kafka 2 passed. New user cases prove one
    business row/marker after duplicate delivery, mappings, version/malformed-input
    direct user DLQ, exact bytes and safe diagnostic headers. Existing quote retry,
    exhaustion, DLQ failure/recovery, crash/idempotency and QuoteExpired cases pass.
17. **End-to-end:** disposable databases/broker, real packaged Gateway, Auth,
    Notification and Audit. Gateway returns 201 with user=1 and PENDING intent while
    publisher disabled. Enabling Auth publisher yields PUBLISHED, one source event,
    one notification/audit and one marker in each service. Key/value match stored
    userId/JSON exactly. No shared durable infrastructure or offsets changed.
18. **Duplicate validation:** explicitly republished exact key/JSON/eventId as a
    second record. Source end advances 1→2, both groups catch up, each business
    table/processed marker remains one. No offset reset.
19. **Topic/group evidence:** [captured descriptions and offsets](phase-19-offset-evidence.txt).
    Both user topics retain 3 partitions / replication factor 1. Normal publication
    adds one source record; consumption adds none. An additional registration with
    a test-only Notification insert failure proved group independence: Audit
    reaches two rows/markers, Notification remains at one and alone publishes a
    user DLQ record after exhaustion. Final source ends `(0,3,0)`, DLQ `(0,1,0)`;
    both groups commit offset 3 on partition 1 with lag zero. Empty partitions stay
    at end 0 and may have no committed offset. Temporary containers/processes removed.
20. **Security:** payload allowlist, raw-event byte comparison, absent producer
    headers, minimal Audit metadata and safe-log scan passed. No credentials are
    persisted in event payloads or diagnostic headers. DLQ deliberately retains
    original input bytes as established in Phase 17; treat DLQ content as sensitive.
21. **Maven:** full `./mvnw clean verify` passed (380 tests at that checkpoint).
    Final `./mvnw verify` passed: **383 tests, zero failures/errors/skips**, including
    three additional scheduler/correlation tests.
    Integration commands:

    ```bash
    ./mvnw -pl services/auth-service,services/notification-service,services/audit-service -Ppostgres-it verify
    ./mvnw -pl services/auth-service,services/notification-service,services/audit-service -Pkafka-it verify
    python3 infrastructure/validation/phase19-e2e.py
    ```

    Normal tests require the existing JVM agent capability; integration tests
    additionally require Docker. Initial sandbox Mockito attachment restrictions
    were resolved by running the same commands with authorized host access.
22. **Limits/deferred:** session locks require direct PostgreSQL/session pooling,
    occupy one connection and serialize batches. A persistently failing oldest
    producer intent may delay later rows; producer retries have no cutoff, cleanup
    or producer DLQ, matching Quote. Delivery and DLQ recovery can duplicate across
    crash windows. Consumer retry counts are local delivery attempts, not durable
    lifetime counts. No backfill, admin status events, refresh tokens, asymmetric
    JWT, real email/SMS, DLQ replay, retry topics, CDC or Pricing events added.
23. **Readiness:** Phase 19 implementation and required validation are complete;
    ready for Phase 20 planning. Phase 20 is not started by this change.

## Repeatable disposable validation

[infrastructure/validation/phase19-e2e.py](../infrastructure/validation/phase19-e2e.py)
uses packaged service jars, dynamic local ports, unique container names, no shared
volumes and finally cleanup. Evidence and metadata-only logs go to a temporary
folder. Test-only Notification constraints exist only inside the disposable DB.

## Created/modified file manifest

- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationRetryTests.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditRetryTests.java`
- `services/notification-service/src/main/resources/application.yml`
- `services/audit-service/src/main/resources/application.yml`- `docs/architecture.md`
- `docs/database-design.md`
- `docs/implementation-roadmap.md`
- `docs/kafka-design.md`
- `docs/phase-19-offset-evidence.txt`
- `docs/phase-19-validation.md`
- `docs/retry-and-dlq.md`
- `docs/specs/README.md`
- `docs/specs/phase-19-auth-user-events.md`
- `docs/transactional-outbox.md`
- `docs/user-registered-event.md`
- `infrastructure/validation/phase19-e2e.py`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/AuditKafkaConfiguration.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/entity/AuditRecord.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuoteDeadLetterRecoverer.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserEventDecoder.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserEventListener.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/UserRegisteredEnvelope.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/exception/InvalidUserEventException.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/service/AuditService.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditKafkaIT.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/UserEventTests.java`
- `services/auth-service/README.md`
- `services/auth-service/pom.xml`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/AuthKafkaConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/config/OutboxSchedulingConfiguration.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/entity/OutboxEvent.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/AuthCorrelationId.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/EventEnvelope.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/event/UserRegisteredData.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/repository/OutboxPollingRepository.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/repository/OutboxRepository.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/AuthOutboxPublisher.java`
- `services/auth-service/src/main/java/com/insurance/platform/auth/service/AuthService.java`
- `services/auth-service/src/main/resources/application-local.yml`
- `services/auth-service/src/main/resources/application.yml`
- `services/auth-service/src/main/resources/db/migration/V4__widen_outbox_correlation_id.sql`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthKafkaIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthOutboxConfigurationTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthOutboxPublisherTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthPostgresIT.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthRegistrationEventTests.java`
- `services/auth-service/src/test/java/com/insurance/platform/auth/AuthServiceApplicationTests.java`
- `services/notification-service/README.md`
- `services/notification-service/src/main/java/com/insurance/platform/notification/config/NotificationKafkaConfiguration.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/QuoteDeadLetterRecoverer.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/UserEventDecoder.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/UserEventListener.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/event/UserRegisteredEnvelope.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/exception/InvalidUserEventException.java`
- `services/notification-service/src/main/java/com/insurance/platform/notification/service/NotificationService.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/NotificationKafkaIT.java`
- `services/notification-service/src/test/java/com/insurance/platform/notification/UserEventTests.java`

## Configured user topics follow-up — 2026-10-18

Auth publication and both user listeners now resolve `user.events.topic`, supplied
by `USER_EVENTS_TOPIC` with default `insurance.user.events`. Both recovery callbacks
receive the same configured source name and `user.events.dlq-topic`, supplied by
`USER_EVENTS_DLQ_TOPIC` with default `insurance.user.events.dlq`. Source recognition
and destination routing no longer depend on hardcoded user topic names. Quote
routing and stable listener/group identities are preserved.

Validation command:

```bash
./mvnw -pl services/auth-service,services/notification-service,services/audit-service -Pkafka-it verify
```

Passed: 120 normal tests (Auth 33, Notification 42, Audit 45), plus 5 Kafka integration
tests (Auth 1, Notification 2, Audit 2), zero failures/errors/skips. Auth's configuration
test proves its wired publisher uses an overridden topic. Both consumer Kafka suites
use `custom.user.events` and a separately named `custom.user.failures` DLQ, proving
listener subscription, duplicate processing, permanent-input recovery, original
bytes/headers and source progress with overrides. Existing quote retry/DLQ matrices
also pass. No separate PostgreSQL-only rerun was needed for this configuration change;
the Kafka integration suites use real disposable PostgreSQL and Kafka.
