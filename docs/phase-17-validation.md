# Phase 17 — Retry and DLQ validation

Date: 2026-10-16. **Complete and ready for Phase 18 — Quote Expiration. Phase 18 has not started.**

## Scope and files

Implemented only in Notification and Audit. Each service adds:

- `event/QuoteDeadLetterRecoverer.java`: Spring Kafka recovery/retry callbacks, exact key/value bytes, safe headers, acknowledged DLQ send.
- `exception/QuoteProcessingException.java`: retryable business failure distinct from invalid input, with a safe underlying class name and no raw cause.
- `*RetryTests.java`: framework attempt classification, byte/header preservation, acknowledgment waiting, failed sends and config bounds.

Modified both Kafka configuration classes, listeners, decoders, application YAML, listener unit fixtures and Kafka integration tests. Updated service READMEs, root/project/Kafka/event/security/architecture/roadmap docs; added [policy](retry-and-dlq.md), [specification](specs/phase-17-retry-and-dlq.md), and this report.

No service/repository/entity idempotency logic, migrations, schemas, pricing/vehicle data, frontend, API Gateway, Auth/Pricing behavior, Quote event contract or outbox semantics changed. Two pre-existing user edits in QuotePersistenceService and OutboxPollingRepository were captured before work and verified byte-for-byte unchanged by this phase; they remain separate working-tree changes.

## Policy verified

- Three total processing attempts by default, fixed 1000 ms delay between retryable failures, configurable with KAFKA_CONSUMER_MAX_ATTEMPTS and KAFKA_CONSUMER_BACKOFF_MS.
- DefaultErrorHandler + FixedBackOff owns redelivery. A focused ConsumerRecordRecoverer publishes to the existing insurance.quote.events.dlq; no handwritten retry loop or retry-topic topology.
- Invalid supported input is non-retryable; valid unknown event types skip successfully. A duplicate processed event succeeds without another business write or DLQ.
- Exact original key/value bytes, including malformed UTF-8, are retained. Headers identify source coordinates, original key, consumer service/group, safe exception class/reason, failed-at time, attempt count, and readable event/correlation IDs. No stack traces or incoming arbitrary/credential headers are copied.
- Both source and DLQ use the Phase 1 three-partition shape; source partition is preserved.
- RECORD mode advances only after processing success or acknowledged DLQ recovery. A failed DLQ send throws and leaves the source replayable. No exactly-once claim.

## Automated results

| Command/check | Result |
| --- | --- |
| `./mvnw -B clean verify` | All six backend modules passed; 361 normal tests, zero failures/errors/skips |
| Notification normal suite | 38 tests passed, including 10 new retry/recovery tests |
| Audit normal suite | 41 tests passed, including 10 new retry/recovery tests |
| `./mvnw -B -pl services/notification-service,services/audit-service -Ppostgres-it verify` | Notification 11 + Audit 12 PostgreSQL integration tests passed |
| `./mvnw -B -pl services/notification-service,services/audit-service -Pkafka-it verify` | One comprehensive real-broker lifecycle test per consumer passed |
| Two packaged consumers, one disposable broker | Independent failure and recovery proof passed |

The existing postgres-it test source was unchanged: it already proves marker/business rollback, successful retry after a rejected insert, duplicate timestamps, historical compatibility and concurrency. Phase 17 additionally induces real PostgreSQL failures through Kafka processing, without introducing Kafka into postgres-it.

Full Maven validation includes existing Quote outbox unit/HTTP tests and both consumer idempotency suites. The Quote producer and outbox implementation were not altered by Phase 17.

## Per-consumer Kafka lifecycle

Each test owns disposable PostgreSQL and Kafka, with source and DLQ topics at three partitions. Manual DLQ inspection uses assignment without a group or offset commits. The source group is isolated (`notification-service-test` / `audit-service-test`). No shared broker or durable local group is used.

| Source partition 0 offset | Scenario | Processing / outcome |
| --- | --- | --- |
| 0 | Simulated process interruption after business DB commit | Test-only stopping handler interrupts progress; same group restarts with the production Phase 17 handler; same source offset returns DUPLICATE, original row/marker timestamps remain |
| 1 | Separate duplicate record, same event ID | One invocation; no additional row/marker or DLQ |
| 2 | FutureEvent type | Intentional skip; no DLQ |
| 3 | Temporary DB CHECK constraint rejects first business insert | Transaction rollback leaves no partial marker; remove disposable constraint; second attempt succeeds, exactly one new business row and marker, no DLQ |
| 4 | Persistent DB CHECK constraint | Exactly three attempts; no new business row/marker; one acknowledged DLQ, source advances to 5 |
| 5 | Invalid UTF-8 value and binary key | One attempt; DLQ preserves both byte arrays exactly |
| 6 | Unsupported QuotePriced version | One attempt; DLQ reason UNSUPPORTED_EVENT_VERSION |
| 7 | Missing event ID | One attempt; DLQ reason INVALID_ENVELOPE |
| 8 | Malformed JSON | One attempt; DLQ reason MALFORMED_JSON |
| 9 | Later valid event | Processes successfully, proving progress continues after DLQ recovery |
| 10 | Malformed event while disposable DLQ topic is unavailable | Actual Kafka send fails; source stays committed at 10; restore destination; acknowledged DLQ allows source to advance to 11 |

Before the destination-outage stage, source end/committed offset is 10 and DLQ end offset is 5. The test deletes only its disposable DLQ, observes actual recovery failure, and recreates the same destination to prove recovery; the restored disposable DLQ contains one record. Final source end/committed offset is 11. Each service database contains three successful business rows and three matching markers; invalid/exhausted events wrote none.

The original Phase 15 post-commit crash/restart evidence remains in the test. The test-only stopping handler is used solely to simulate that interruption; normal Phase 17 behavior is restored before subsequent cases. It is not production configuration.

## Independent groups: real two-service validation

Ran the freshly packaged Notification and Audit applications as separate processes against one new disposable Kafka and one new disposable PostgreSQL with separate `notification_it` and `audit_it` databases. Both used their real production listener/configuration/recovery code. HTTP ports were random loopback ports. Source and DLQ were the standard names, each three partitions.

Only Notification's producer bootstrap address was deliberately pointed to unavailable `127.0.0.1:1`; its consumer still used the real broker. Audit used the real broker for both consumption and DLQ publication. Published one deliberately malformed input with a unique key to source partition 0 offset 0.

Observed before restoring Notification:

| Item | Value |
| --- | --- |
| Source end offset | 1 |
| Audit group committed offset | 1 |
| Notification group committed offset | 0 |
| DLQ end offset | 1 |
| DLQ service header | audit-service |
| Both business/processed tables | Empty |

Stopped only the disposable Notification process, restored its producer address and restarted the **same group**, without resetting offsets or publishing another source record.

Observed after recovery:

| Item | Value |
| --- | --- |
| Source end offset | Still 1 |
| Audit committed offset | Still 1 |
| Notification committed offset | 1 |
| DLQ end offset | 2 |
| DLQ service headers | One audit-service, one notification-service |
| Key/value bytes | Both exactly equal to the original source record |
| Source coordinate headers | Both identify original partition 0 / offset 0 |
| Attempt headers | 1 for this permanent invalid input |
| Business rows and processed markers | Zero in both databases |

This proves a failed DLQ producer in one group does not prevent the other group from advancing. It also proves the blocked source record remains replayable and later recovers without another source append.

Temporary helper and evidence paths used for this isolated run: `/tmp/Phase17Independent.java`, `/tmp/phase17-independent.log`, `/tmp/phase17-independent-result.json`. Application logs were kept under `/tmp/phase17-independent-*.log`. All task-owned application processes and disposable containers were stopped/removed by the validation harness.

## Shared runtime and safety

Notification PID 87741 on 8084 and Audit PID 87870 on 8085 were already running older code. They were left untouched. Shared-broker poison-message testing was not performed because it would interrupt those older consumers; equivalent failure scenarios and independent groups were validated in disposable infrastructure instead.

No shared PostgreSQL writes, constraints, migrations, fixture accounts, topic appends, topic deletions, group resets, historical replay, partition-count changes or replication-factor changes were performed. No new shared DLQ consumer group was created. Existing user services remain running, with their existing code until the user restarts them. No credentials or tokens were introduced into repository files.

## Remaining limits and deferred work

- Retry state/count is in memory and can restart after process loss/rebalance or failed DLQ recovery. DLQ unavailability intentionally keeps the source replayable, so there is no global delivery cutoff.
- Blocking retries can delay partitions assigned to that consumer thread; other groups are independent.
- Acknowledged DLQ send followed by source-commit failure can produce duplicate DLQ records. A timeout can also have an ambiguous send outcome.
- Existing partition compatibility is assumed for source-topic overrides; a bad destination does not silently complete the source record.
- No replay tooling/API/consumer, retry topics, backoff tiers, exponential framework, outbox redesign/cleanup, new event types, metrics expansion, or Phase 18 implementation.

Changes are uncommitted. See [retry/DLQ operations](retry-and-dlq.md) before inspecting or restarting consumers.
