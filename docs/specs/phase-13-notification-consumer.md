# Phase 13 — Notification Consumer

Status: Complete. See [validation evidence](../phase-13-validation.md).

## Goal and scope

Consume existing QuotePriced v1 JSON from `insurance.quote.events` in Notification Service and write a simulated notification. Preserve all Phase 12 producer fields, existing schemas/topics, other service behavior and frontend code. No Audit consumer or subsequent phase work.

## Functional behavior

Use configurable dedicated group `notification-service`. Explicit DTO mapping must validate the envelope and payload, including event ID, version, aggregate/quote identity equality, customer ID and premium/currency. Valid events create one CREATED/QUOTE_PRICED notification with a generated UUID, reference/premium/currency message, source event/quote/customer IDs and injected UTC processing timestamps. Unknown types are deliberately skipped and logged; invalid JSON/data/version stops the listener without treating the failed record as complete. Kafka key mismatch is diagnostic; payload identity determines mapping.

## API, database and Kafka impact

No new REST endpoint, HTTP client, UI, migration or topic. Write only existing notification_db.notifications. Preserve UNIQUE(event_id). Never write processed_events, any other service DB, outbox or Flyway history through application logic. Topic has three partitions and replication factor one locally. Default earliest demonstrates historical consumption; existing committed offsets are honored.

Use enable-auto-commit=false and RECORD acknowledgment. A separate transactional service commits PostgreSQL before the listener returns. Spring's stopping error handler avoids default retries/recovery and stops on failure. No manual commits, retry/backoff framework, DLQ, XA/2PC or Kafka/PostgreSQL atomicity. Duplicate delivery can fail on the unique constraint and stop; idempotency belongs to Phase 15.

## Security

Fixed JSON DTO mapping without polymorphic type headers; no JPA deserialization. Preserve existing HTTP deny-by-default security. Log fixed reason codes and safe metadata, never raw payloads, credentials or nested exception messages. No external notification provider or HTTP service calls.

## Testing requirements and Definition of Done

Normal tests and full Maven build must run without Docker. Add opt-in disposable PostgreSQL and Kafka tests for exact mapping, persistence, rollback, uniqueness, empty processed_events, real consumption and offset advancement/non-advancement. Live validate Gateway → Quote → Kafka → Notification using a uniquely identifiable temporary customer and quote. Inspect topic/group offsets before and after, preserve topic settings/history and unrelated data, document historical consumption and cleanup. Stop task-owned application processes. Phase 14 stays unstarted.
