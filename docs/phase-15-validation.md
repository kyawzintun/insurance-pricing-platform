# Phase 15 — Idempotent Consumers Validation

Completed 2026-10-14. No Phase 16 implementation and no commit created.

## Automated results

| Command | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six modules passed; 334 normal tests, zero failures/errors/skips |
| `./mvnw -B -pl services/notification-service,services/audit-service -Ppostgres-it verify` | Notification 11 + Audit 12 PostgreSQL tests passed |
| `./mvnw -B -pl services/notification-service,services/audit-service -Pkafka-it verify` | One Kafka/PostgreSQL test per consumer passed |
| `git diff --check` | Passed |

The normal build remains Docker-independent. Integration profiles ran separately on disposable PostgreSQL 17.11-bookworm and Kafka 4.1.2. Existing service startup, event validation/mapping and unrelated application tests passed. No frontend or other application production code changed.

Six new normal idempotency tests per consumer verify claim-before-write order, duplicate skip, legacy compatibility, marker failure preventing business mutation, propagated business failure, and duplicate-as-success metadata logging. Existing event mapping tests now include the marker claim.

## Real PostgreSQL atomicity and concurrency

Both profiles prove:

- First event creates one business row plus one marker with eventId/stable consumer name/Clock time.
- Duplicate returns DUPLICATE with exactly the same business row and original processed_at.
- Different event IDs both process; UNIQUE(event_id) still rejects direct conflicting business mutations.
- The same eventId with different consumer names can produce separate marker rows.
- Marker registration outside a transaction is rejected by MANDATORY propagation.
- Business insert failure rolls back the marker; after removing the disposable test constraint, processing succeeds.
- Marker failure prevents the business insert; outer transaction rollback removes both writes.
- A simulated historical business row without a marker gains one on replay and is not rewritten.
- A concurrent duplicate waits for the winner's transaction. Winner commit produces one PROCESSED and one DUPLICATE; winner rollback lets the waiting transaction process. Both outcomes leave exactly one business row and one marker.

Forced constraints and fixture marker removal exist only in disposable tests. No production migration or constraint change was added.

## True same-record Kafka redelivery

For each consumer, the test publishes one record at partition 0 offset 0 before starting the group. A test-only listener spy calls the real listener, waits for its separate transactional service to commit, then throws before listener return/offset commit. The container stops, leaving one business row and one marker, with offset 0 not successfully committed.

Restarting the same group without any offset reset or extra publication invokes the listener again with the identical partition 0, offset 0 and JSON. The marker conflicts, processing returns duplicate success, and committed next offset reaches 1. The row and marker timestamp remain unchanged.

A second, separately published record with the same eventId then advances to offset 2 without another business row. An unsupported type is intentionally skipped to 3; malformed JSON at offset 3 stops the container with committed offset still 3 and topic end 4. This distinguishes true same-offset redelivery from two distinct Kafka records carrying one eventId. Test group names differ from the stable persisted logical consumer names, proving that deduplication identity is not derived from group overrides.

## Live original + duplicate record

The existing Auth, Pricing, Quote and Gateway continued running unchanged. Captured SQL fingerprints for all relevant tables (including both processed_events tables), Flyway histories, topic configuration/end offsets and both groups. Restarted only Notification and Audit with Phase 15, using their existing group names and committed offsets.

Registered/logged in one temporary fictional CUSTOMER and created a quote through Gateway (HTTP 201). Both services created exactly one business row and marker. Then a validation-only Kafka console producer appended the **identical key and JSON bytes**, preserving eventId and payload. No shared offset was reset.

| Evidence | Value |
| --- | --- |
| Shared event ID | `529d8322-9582-442f-8e9d-a272a30a3bb1` |
| Quote ID / Kafka key | `7ae1ceec-4dfb-48d7-9492-899c12e3d665` |
| Correlation ID | `phase15-test-0a065c6197354bf385214457cac9c1da` |
| Original / duplicate record | partition 1, offsets 2 / 3 |
| Notification marker identity | eventId + notification-service |
| Audit marker identity | eventId + audit-service |
| Notification original processed_at | `2026-10-14T10:18:50.89986+00:00` |
| Audit original processed_at | `2026-10-14T10:18:50.896725+00:00` |

Both logs showed PROCESSED for the original and DUPLICATE for the second record. Each DB still contained exactly one business row and one marker for this event. Full captured database fingerprints after the first record and after the duplicate were identical, proving no duplicate mutation or timestamp refresh. Separate local processed_events state allowed both consumers to process the same event independently.

## Consumer group and topic evidence

| Group | Partition 0 committed/end | Partition 1 committed/end | Partition 2 committed/end | Lag |
| --- | --- | --- | --- | --- |
| notification-service | 2 / 2 | 4 / 4 | 1 / 1 | 0 |
| audit-service | 2 / 2 | 4 / 4 | 1 / 1 | 0 |

Partition 1's end advanced from 2 to 3 for the new quote event, then 3 to 4 for the explicit validation duplicate. Other domain/DLQ end offsets did not change. Consumer processing itself did not append or alter records; both groups independently advanced past the duplicate. Topic settings, partition counts and replication were unchanged.

## Historical compatibility and safety

No historical scan/backfill or migration ran. On a legacy event replay, after an atomic new marker claim, existsByEventId recognizes the historical business row and commits only the new marker. The business row remains unchanged; the marker records the first tracked replay time. This avoids catching a business unique violation inside an aborted transaction. Existing marker conflicts skip business lookup entirely. Genuine SQL/validation failures remain failures.

Narrow cleanup removed only the temporary account, quote aggregate, notification/audit rows and their two associated markers. **All captured database fingerprints returned to baseline**, including historical business rows, preexisting markers, outboxes, pricing/catalog data and all Flyway histories. Both validation Kafka records and committed offsets remain durable. Deliberately replaying these cleaned-up test records later could recreate their business rows/markers; normal restart resumes after them.

Stopped the two task-owned consumer process groups; ports 8084/8085 were verified free. Original Gateway/Auth/Quote/Pricing processes remain running on 8080/8081/8082/8083. Shared Docker infrastructure remains running. No Kafka history was deleted and no offset was reset.

## Limits and readiness

Kafka delivery is still at least once; this implements idempotent local database effects, not Kafka exactly-once semantics. Markers and business writes share one local transaction, but DB and Kafka offset commits remain separate. Invalid supported events and real database failures still stop consumption; retry/DLQ remains Phase 17. Marker retention/cleanup policy is not introduced. Quote producer loss between DB commit and publishing remains for Phase 16 Outbox.

No retry/backoff, DLQ, Outbox, new event type, producer changes, shared deduplication database, locks, REST/UI or schema changes were added. Phase 15 is complete and ready for Phase 16 — Transactional Outbox; Phase 16 has not started.
