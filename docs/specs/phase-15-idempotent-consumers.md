# Phase 15 — Idempotent Consumers

Status: Complete. See [validation evidence](../phase-15-validation.md).

## Goal and scope

Make repeated QuotePriced delivery successful for Notification and Audit without duplicate business writes. Preserve the envelope/version/topic/key, group configuration, schemas, existing mapping/security behavior and all other applications. No Phase 16 work.

## Transaction and deduplication design

Each service has a small local ProcessedEventRepository. Its parameterized native INSERT uses ON CONFLICT (event_id, consumer_name) DO NOTHING and returns whether a marker was inserted. MANDATORY propagation joins the service's local JPA transaction. Logical names are stable constants notification-service and audit-service, independent of instance and Kafka group overrides.

The existing transactional service first claims the marker. Conflict returns DUPLICATE without another business lookup or mutation. A newly acquired claim checks for an existing business row by eventId for legacy compatibility. If present, commit the marker and return DUPLICATE; otherwise insert the business row and return PROCESSED. Marker and business transaction commit before listener completion, preserving RECORD acknowledgment and disabled auto-commit. No independent marker transaction, distributed lock, shared DB, or select-first uniqueness strategy.

PostgreSQL's composite primary key handles concurrent attempts. A competing insert waits for commit/rollback: committed winner means duplicate, rollback allows another attempt to process. Business UNIQUE(event_id) constraints stay intact. Genuine failures roll back the marker and business work and retain existing stop/uncommitted-offset behavior.

## Time, historical records and logging

Use one injected UTC/microsecond instant for a new marker and business creation. Duplicate conflicts do not update processed_at. For a legacy business row without a marker, processed_at is the first tracked replay time; do not invent the original historical processing time. No startup backfill or scan. INFO logs distinguish PROCESSED and DUPLICATE using event/type/logical-consumer/quote/correlation/partition/offset metadata, without payloads/secrets.

## API/database/Kafka impact

No API/UI, HTTP client, schema migration, event change, producer code, new topic/group or offset reset. Each consumer writes only its business table and its existing local processed_events table. Unknown types still skip intentionally without markers; malformed supported events and unsupported versions still fail. No retry/backoff, DLQ or Outbox.

## Tests and Definition of Done

Normal Maven validation remains Docker-independent. Unit tests cover first/duplicate/legacy branches, marker failure before business mutation, propagated business failure and duplicate-as-success logs. Existing mapping/validation/startup tests remain.

Real PostgreSQL tests cover exact markers, unchanged timestamps, different event IDs, independent consumer-name keys, mandatory transaction boundary, business/marker failure rollback, outer rollback, legacy rows, concurrent winner commit and rollback. Keep previous field/JSONB/correlation coverage.

Kafka tests must simulate a failure in the listener after the transactional service has committed but before the listener returns. After container stop, restart the same group without resets: it receives the identical partition/offset again, returns duplicate successfully and advances. Separately publish the same eventId in another record. Assert one business row/marker and unchanged original timestamp. Invalid JSON must still stop without advancing past the failure.

Live validate one new quote through Gateway and one validation-only identical event/key/payload publication. Verify one business row and one stable-name marker in each DB, both groups advancing, and no DB changes from the duplicate. Do not reset shared offsets or bulk-backfill history. Capture fingerprints/topic configuration before/after, narrowly clean temporary data if used, retain Kafka history/offsets, and stop validation consumer instances. Document remaining at-least-once delivery and deferred retry/DLQ/Outbox work. Do not start Phase 16.
