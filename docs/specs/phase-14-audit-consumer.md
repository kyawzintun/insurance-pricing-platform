# Phase 14 — Audit Consumer

Status: Complete. See [validation evidence](../phase-14-validation.md).

## Goal and scope

Consume QuotePriced v1 in Audit Service's own group and persist audit_records, independently of Notification. Preserve Notification/Quote/Auth/Pricing/Gateway/frontend behavior, existing topics, and the producer contract. No Phase 15 work.

## Functional behavior

Default topic insurance.quote.events, group audit-service, earliest reset. Explicitly deserialize the existing envelope into consumer-owned records and validate event/version/aggregate/actor IDs, occurredAt, producer, reference, status, premium/currency and quote identity consistency. Skip unsupported types with safe logs. Malformed supported events, unsupported versions and database failures stop consumption without skipping the failed offset.

Map eventId/eventType, CUSTOMER/customerId actor, Quote/quoteId entity, correlation ID, occurrence time and producer. Store an allowlisted seven-field JSONB metadata object (reference/status/coverage/premium/currency/pricedAt/expiresAt) and an injected UTC processing timestamp. No HTTP clients, service DB lookups, producer, or public Audit API.

## Database and Kafka impact

Write only audit_records. Preserve UNIQUE(event_id), leave processed_events unused. Local @Transactional service commits before the listener returns; RECORD acknowledgment with auto-commit disabled follows persistence. Use the same stopping error handler policy as Notification; no retries/recovery, DLQ, Kafka transaction, XA/2PC or idempotency.

One real schema compatibility blocker requires a minimal service-owned V3 migration: widen correlation_id from VARCHAR(100) to VARCHAR(128), matching the already-approved Phase 12/Gateway contract. Preserve applied V1/V2 migrations and checksums; only append the new Flyway history entry. No producer contract or topic changes.

## Security and limitations

Fixed DTO mapping, no wildcard Java type deserialization. Persist only allowed metadata, with no credentials or full driver PII. Log safe codes/identifiers, not raw JSON or nested exception messages. Actor attribution comes from event data, not a new authentication mechanism.

If the DB commits but the offset does not, a duplicate insert can violate uniqueness and stop processing. processed_events-based idempotency belongs to Phase 15. Retry/DLQ and Outbox remain deferred. Historical events can reference deleted temporary data without requiring external lookups.

## Validation and Definition of Done

Normal Maven build remains Docker-independent, with Audit startup, mapping, safe errors and validation tests. Disposable PostgreSQL proves UUID/string/time/JSONB mapping, correlation width, uniqueness, rollback and no processed_events writes. Disposable Kafka proves earliest, commit behavior, intentional skip, duplicate failure and malformed event handling.

Live create one quote through Gateway; Notification and Audit must independently persist matching event/quote/customer IDs, with Audit preserving correlation and occurrence time. Inspect both groups separately, compare topic end offsets and database fingerprints before/after, preserve historical records/topics/offsets, and clean up only narrowly identified temporary rows. Stop task-owned Audit while preserving preexisting user services. Document the necessary migration separately from unchanged schema/history. Do not start Phase 15.
