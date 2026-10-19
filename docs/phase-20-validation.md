# Phase 20 — Pricing Events validation

Validated 2026-10-19 (Asia/Bangkok). Phase 20 only; Phase 21 is not started.

## Implementation report

1. **Files:** the complete created/modified file manifest is below. Production scope
   is Pricing plus Audit; Notification, Auth, Quote, Gateway routing and frontend
   production code are unchanged. A disposable validation script is added.
2. **Contracts:** PricingRuleCreated, PricingRuleUpdated, PricingRuleEnabled and
   PricingRuleDisabled v1. Common nine-field envelope, PricingRule aggregate,
   rule UUID aggregateId/key, pricing-service producer. Compact resulting state:
   pricingRuleId, ruleType, operator, comparisonValue/comparisonValueTo, factor,
   fixedAmount, effectiveFrom, enabled, persisted version, changedBy, changedAt.
   Description is deliberately omitted. See [contract](pricing-rule-events.md).
3. **Transaction:** existing @Transactional admin methods mutate and flush rules,
   then insert the event through OutboxRepository in that same local transaction.
   Any outbox failure rolls back the mutation. Optimistic-lock failure occurs before
   event insertion. HTTP success follows commit, independently of Kafka publication.
4. **ADMIN identity:** PricingAdminIdentity reads authenticated JwtAuthenticationToken
   authorities and UUID subject. No request body actor field is accepted. Existing
   independent Pricing/Gateway ADMIN authorization remains. An invalid UUID actor
   receives safe 403 without an event; ordinary missing/CUSTOMER HTTP identity remains
   401/403. Service calls in disposable tests explicitly establish ADMIN context.
5. **Correlation/time:** PricingCorrelationId preserves valid Gateway header IDs up
   to 128 characters and generates a UUID fallback for direct calls. Each mutation
   captures one injected UTC microsecond instant for occurredAt/changedAt/outbox
   created_at. Publisher retries never reconstruct these fields. Existing rule
   updatedAt monotonic behavior remains unchanged.
6. **Outbox:** existing pricing_db.outbox_events, immutable serialized envelope,
   PENDING/retry_count=0/published_at=null at insert. No second table or historical
   backfill. New events apply only to successful post-deployment admin writes.
7. **Publisher:** service-local Quote/Auth pattern: bounded oldest-first batch,
   PostgreSQL session advisory lock `(741020, 1)`, competing poll skips, one borrowed
   autocommit connection, no open DB transaction across Kafka I/O. Settings use
   PRICING_OUTBOX_PUBLISHER_ENABLED/POLL_INTERVAL/BATCH_SIZE. PRICING_EVENTS_TOPIC
   defaults to insurance.pricing.events, shared with Audit's listener/routing config.
8. **At-least-once:** exact stored JSONB text, rule UUID key and no producer headers.
   Acknowledgment precedes PUBLISHED/published_at. Failed sends remain PENDING and
   increment retry_count. Send success plus failed status update/crash can resend
   the identical event. No exactly-once claim.
9. **Audit:** focused consumer-owned PricingRuleEnvelope/decoder/listener. Exact
   event type; ADMIN/changedBy actor; PricingRule/rule UUID entity; envelope
   producer/correlation/occurredAt. Fixed DTO is the compact metadata allowlist.
   Existing processed_events/eventId + audit-service marker and business row commit
   together. Different event IDs for the same rule each produce legitimate audit rows.
10. **Retry/DLQ:** existing DefaultErrorHandler/FixedBackOff and recovery headers.
    Invalid supported versions/data go directly to the configured pricing DLQ;
    processing/database failures use bounded retries. Unknown nonempty types skip.
    Exact original key/value/partition and allowlisted safe headers are retained.
    Recovery acknowledgment gates source completion; DLQ outage leaves source replayable.
    PRICING_EVENTS_DLQ_TOPIC defaults to insurance.pricing.events.dlq. Quote/user
    routing and stable listener/group/processed identities remain unchanged.
11. **Migration:** append-only Pricing V4 widens only outbox correlation_id from 100
    to 128 to honor the established Gateway contract. Old migrations and seeds are
    unchanged. No Audit schema migration is needed.

## Executed verification

12. **Normal tests added:** four-event HTTP snapshots and authenticated actor;
    correlation/time/version/envelope/security fields; rejected body actor and invalid
    JWT subject; no intent for validation, stale expected-version and flush conflict;
    no intent for missing/CUSTOMER identity. Publisher tests cover acknowledgments,
    synchronous/asynchronous send failures, status-update failure, identical retries,
    bounded batches, contention, empty work, config/scheduler and safe logs. Audit
    decoder tests cover all four types, unknown skip and permanent-invalid input.
13. **Pricing PostgreSQL — 4 passed:** CRUD versions/calculation/filter regressions;
    persisted event versions 0–4 and full 128-character correlation; a real simultaneous
    optimistic-lock race; forced outbox failure rolling back create/update/enable/
    disable; outer rollback; bounded ordered polling, advisory-lock exclusion,
    retry count and immutable payload/eventId through PUBLISHED transition. Flyway
    has four successful migrations, preserving the original three.
14. **Pricing Kafka — 1 passed:** disposable PostgreSQL + Kafka, create/update intents,
    verbatim stored JSON and rule key, same partition/later offset, acknowledgment
    followed by PUBLISHED/published_at, already-PUBLISHED work ignored, and actual
    broker pause/outage/recovery preserving durable intent and event identity.
15. **Audit Kafka — 3 passed:** focused pricing case creates four legitimate ADMIN/
    PricingRule audit rows and markers, absorbs duplicate delivery, and directly
    recovers unsupported versions/malformed JSON to an independently configured
    pricing DLQ with original bytes and headers. Existing Quote/User cases remain
    green, including the Phase 17 retry/exhaustion/recovery matrix. Audit PostgreSQL
    regression suite also passed all 12 tests.
16. **Optimistic locking:** PostgreSQL barriers force two transactions to load the
    same version before admin update: one success, one optimistic conflict, exactly
    one Updated intent. HTTP tests cover both expected-version and Hibernate-flush
    conflicts. Disposable Gateway requests using the same expected version returned
    200 + 409 and produced one additional Updated intent. Failure events are not emitted.
17. **End-to-end:** real packaged Gateway/Auth/Pricing/Audit with disposable broker
    and three databases. Register temporary CUSTOMER, explicitly promote locally,
    log in again for ADMIN JWT. Create through Gateway with publisher paused: rule
    and PENDING intent exist, topic end=0. Enable publisher: PUBLISHED and one Audit
    row. Update/disable/enable yield four distinct event IDs, versions 0/1/2/3 and
    four Audit rows/markers. Unauthenticated/CUSTOMER mutations return 401/403 with
    no pricing intent. Temporary processes/containers are removed in finally.
18. **Duplicate:** after the four core operations and one winning race update,
    replay the exact first event key/JSON/eventId. Topic end advances 5→6; Audit
    business rows and processed markers remain five. Offset advances over duplicate;
    no shared group offsets are reset.
19. **Topic/group evidence:** [captured descriptions/end offsets](phase-20-offset-evidence.txt).
    Pricing source/DLQ retain 3 partitions and replication factor 1. Source totals:
    0→1 after create, →4 after the four operations, →5 after the race winner, →6 after
    explicit duplicate publication. Consumer processing appends nothing to source.
    This run hashed the rule to partition 1: final source ends `(0,6,0)`, DLQ `(0,0,0)`;
    Audit commits partition 1 offset 6 with lag 0. Exact partition number is not a
    contract; empty partitions can have no committed offset. Invalid-input pricing
    DLQ records are verified separately in Kafka IT on custom topics.
20. **Security/data safety:** event data is a fixed rule snapshot, never a JPA entity,
    raw JWT, Authorization header, credentials, password, signing key or token DTO.
    Both HTTP/event assertions and disposable payload/application-log scans pass.
    ADMIN UUID is intentionally retained. PostgreSQL rejected-row detail logging
    and Kafka's payload-printing default error listener are disabled for Pricing.
    DLQ retains original bytes by design and must be handled as potentially sensitive.
21. **Maven:** `./mvnw clean verify` passed **403 tests, zero failures/errors/skips**:
    Gateway 42, Auth 33, Quote 115, Pricing 124, Notification 42, Audit 47. Existing
    synchronous Quote/Pricing regression tests stay green. No PricingCalculated event
    is added; disposable calculation output matches baseline and outbox count stays
    unchanged. Final profile commands are listed below.
22. **Known limits/deferred:** shared session lock serializes dispatch and borrows one
    connection; direct PostgreSQL/session pooling is required. A persistently failing
    oldest producer intent can delay later work. Producer retries have no cutoff/DLQ,
    retention or cleanup, matching the established design. Duplicate delivery and DLQ
    recovery remain possible across crash windows. Consumer attempt state is not a
    durable lifetime counter. No deletion/bulk/calculation events, pricing notifications,
    backfill, customer management, authentication redesign, replay tooling, retry
    topics, CDC or Schema Registry.
23. **Readiness:** Phase 20 implementation and required validation are complete and
    ready for Phase 21 — Admin Customer Management. Phase 21 has not been started.

## Reproduction

```bash
./mvnw clean verify
./mvnw -pl services/pricing-service -Ppostgres-it verify
./mvnw -pl services/pricing-service -Pkafka-it verify
./mvnw -pl services/audit-service -Ppostgres-it verify
./mvnw -pl services/audit-service -Pkafka-it verify
python3 infrastructure/validation/phase20-e2e.py
```

Normal tests need the project's existing Mockito JVM-agent capability. Integration
profiles require Docker. The [disposable E2E script](../infrastructure/validation/phase20-e2e.py)
uses packaged jars, random local ports, uniquely named containers, no shared volumes
and finally cleanup. Only test databases are altered for local ADMIN provisioning.
No durable shared database, topic, seed row or consumer-group offset is modified.

## Created/modified file manifest
- `docs/architecture.md`
- `docs/database-design.md`
- `docs/implementation-roadmap.md`
- `docs/kafka-design.md`
- `docs/phase-20-offset-evidence.txt`
- `docs/phase-20-validation.md`
- `docs/pricing-rule-events.md`
- `docs/retry-and-dlq.md`
- `docs/specs/README.md`
- `docs/specs/phase-20-pricing-events.md`
- `docs/transactional-outbox.md`
- `infrastructure/validation/phase20-e2e.py`
- `services/audit-service/README.md`
- `services/audit-service/src/main/java/com/insurance/platform/audit/config/AuditKafkaConfiguration.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/PricingEventDecoder.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/PricingEventListener.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/PricingRuleEnvelope.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/event/QuoteDeadLetterRecoverer.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/exception/InvalidPricingEventException.java`
- `services/audit-service/src/main/java/com/insurance/platform/audit/service/AuditService.java`
- `services/audit-service/src/main/resources/application.yml`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditKafkaIT.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/AuditRetryTests.java`
- `services/audit-service/src/test/java/com/insurance/platform/audit/PricingEventTests.java`
- `services/pricing-service/README.md`
- `services/pricing-service/pom.xml`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/OutboxSchedulingConfiguration.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/config/PricingKafkaConfiguration.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/entity/OutboxEvent.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/event/EventEnvelope.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/event/PricingAdminIdentity.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/event/PricingCorrelationId.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/event/PricingRuleEventData.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/exception/ApiExceptionHandler.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/repository/OutboxPollingRepository.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/repository/OutboxRepository.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/service/PricingOutboxPublisher.java`
- `services/pricing-service/src/main/java/com/insurance/platform/pricing/service/PricingRuleAdminService.java`
- `services/pricing-service/src/main/resources/application-local.yml`
- `services/pricing-service/src/main/resources/application.yml`
- `services/pricing-service/src/main/resources/db/migration/V4__widen_outbox_correlation_id.sql`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingAdministrationTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingKafkaIT.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingOutboxConfigurationTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingOutboxPublisherTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingRulePostgresIT.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingServiceApplicationTests.java`
- `services/pricing-service/src/test/java/com/insurance/platform/pricing/PricingTestIdentity.java`
