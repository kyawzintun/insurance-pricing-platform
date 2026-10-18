# Phase 19 — Auth/User Events

Implement UserRegistered v1 only, following the existing producer outbox and
consumer idempotency/retry architecture. See [contract](../user-registered-event.md)
for schema, flow, privacy, configuration, concurrency and failure semantics.

Registration must commit users + outbox_events together, roll back both on failure,
and perform no Kafka send on the HTTP path. Preserve all prior Auth/JWT/password
behavior. Use canonical email and one injected UTC registration instant; preserve
valid incoming correlation IDs up to 128 characters. Only Auth V4 is required.

Publish immutable stored JSON to insurance.user.events keyed by user UUID. Poll
bounded ordered batches under a session advisory lock without a transaction across
Kafka I/O. Mark PUBLISHED only after acknowledgment. Preserve at-least-once retries.

Notification records USER_REGISTERED/CREATED with null quote ID; Audit records
UserRegistered/CUSTOMER with User entity and role/status metadata. Reuse the same
local processed_events transactions and Phase 17 recovery headers/error handler;
route user failures to insurance.user.events.dlq. Unknown valid types still skip.

Validation: normal Maven, disposable Auth PostgreSQL/Kafka, consumer Kafka regressions,
Gateway registration through both consumers, duplicate replay and topic/group evidence.
See [validation results](../phase-19-validation.md). No historical backfill, admin
status events, authentication redesign, real messages or Phase 20 work.
