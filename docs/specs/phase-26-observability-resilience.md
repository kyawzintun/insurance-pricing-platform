# Phase 26 — Observability & Resilience

Phase 26 adds application-level visibility and bounded failure handling only. Pricing rules,
event envelopes, JWT architecture, local transaction boundaries, Kafka acknowledgment,
idempotency, retry/DLQ and outbox advisory locks are unchanged. Phase 27 is not implemented.

```text
Customer request
    ↓
Gateway [correlation]
    ↓
Quote
    ↓
Pricing client
    ├── explicit connect/read timeout
    ├── circuit breaker
    └── one existing 401 refresh; no transport retry
    ↓
Pricing

Kafka / Outbox → safe logs + metrics → operational visibility
```

## Logs and correlation

Every service uses a text pattern with `service` and `correlationId`. Servlet services scope
MDC to the existing bounded X-Correlation-ID, return it in response headers, and restore
thread context in finally. Direct requests without a valid ID get one UUID, reused by
outbound calls and event factories. Gateway logs its captured ID explicitly because Reactor
callbacks can change threads; it does not put request data in thread-local MDC.
Consumer observation scopes the validated event correlation ID around the transactional
service invocation and restores prior MDC even on failure. Completion logs retain explicit
event ID, aggregate ID, correlation ID and topic/partition/offset. Outbox completion/failure
logs retain outbox ID and event metadata. Existing consumer retry/exhaustion/DLQ logs and
framework container lifecycle logs remain in use.

Request logs contain method/status only: no URL parameters, bodies, headers, passwords,
client secrets, JWTs, service/refresh tokens or private keys. Outbox errors log exception
class only, never exception text/stack traces or payload. IDs are diagnostics, never identity.
Avoid enabling HTTP wire or payload DEBUG logging in production.

## Health and security

All six services expose GET `/actuator/health`, `/actuator/info`,
`/actuator/health/liveness`, `/actuator/health/readiness` publicly with details hidden.
Only health, info and metrics are exposed. env/configprops/heapdump/threaddump remain
unexposed. `/actuator/metrics` and its children require ADMIN in Gateway, Auth, Quote,
Pricing and Audit. Gateway exposes only its own metrics, never routes downstream Actuator.
Notification has no user JWT architecture; its numeric metrics are directly readable on
its internal service port. Restrict that port with deployment networking. No secrets or
request IDs are metric labels.

| Service | Readiness |
| --- | --- |
| Gateway | Spring application readiness state |
| Auth, Quote, Pricing | Spring readiness state + own PostgreSQL datasource |
| Notification, Audit | Spring readiness state + own PostgreSQL + local listener containers running |

Liveness uses only application liveness state. Downstream Auth/Pricing outages do not make
Quote unready; Kafka outages do not block quote creation. Consumer container health indicates
running local containers, not broker reachability, partition assignment, lag or successful
business delivery. Deliberately disabled listeners report UNKNOWN. Pending outbox/processing counters complement it. Health database checks
can wait for the datasource acquisition timeout. The database-free unit test profile overrides
readiness membership; integration validation uses actual database indicators.

## Metrics

Use GET `/actuator/metrics` to list available metrics; GET `/actuator/metrics/<name>` to inspect.
Custom metrics are process-local, reset on restart, and have no high-cardinality identifier tags.
Standard JVM, HTTP, datasource and Kafka instrumentation remains available through Actuator.

| Name | Meaning |
| --- | --- |
| insurance.quote.created | Orchestrated creation returned after persistence commit |
| insurance.quote.failed | Creation orchestration threw; excludes pre-controller security/JSON failures |
| insurance.pricing.client.duration | Entire calculation attempt including token acquisition/401 refresh; timer count/total/max |
| insurance.pricing.client.errors | Calculation failures including token acquisition and open-circuit rejection |
| insurance.pricing.circuit.state | One-hot gauge tagged state=closed/open/half_open |
| insurance.outbox.published | Broker acknowledged and PUBLISHED status update succeeded |
| insurance.outbox.failed | Failed send, status update or polling attempt; operational failures, not unique events |
| insurance.outbox.pending | Cached PENDING row count; -1 on sampling failure/before first sample |
| insurance.outbox.oldest.pending.seconds | Cached oldest PENDING age; 0 if empty, -1 if unknown |
| insurance.kafka.consumer.processed | Supported event transaction completed with PROCESSED |
| insurance.kafka.consumer.duplicate | Supported event transaction returned DUPLICATE |
| insurance.kafka.consumer.dlq | DLQ publication acknowledged (may repeat on redelivery) |

Counters appear after first activity. Pending/age gauges exist only with publisher enabled,
and sample every 30 seconds (`outbox.metrics.interval` in milliseconds). Scrapes never query
PostgreSQL. Sampling scans pending rows and is intended for the current learning-scale outbox;
no new index or tuning was added. They can lag activity by one interval. A broker acknowledgment
followed by status failure counts as failed, remains PENDING, and may be delivered twice.
No automatic mutation/requeue/deletion or stuck-age warning is added.

## HTTP resilience

| Environment variable | Default |
| --- | --- |
| PRICING_CONNECT_TIMEOUT | 2s |
| PRICING_READ_TIMEOUT | 5s |
| AUTH_TOKEN_CONNECT_TIMEOUT | 2s |
| AUTH_TOKEN_READ_TIMEOUT | 5s |
| GATEWAY_HTTP_CONNECT_TIMEOUT_MS | 2000 |
| GATEWAY_HTTP_RESPONSE_TIMEOUT | 30s |
| PRICING_CB_FAILURE_RATE_THRESHOLD | 50 |
| PRICING_CB_MIN_CALLS | 5 |
| PRICING_CB_SLIDING_WINDOW_SIZE | 10 |
| PRICING_CB_OPEN_WAIT | 10s |

Quote uses the existing JDK HTTP request factory with positive connect/read timeouts. Auth
now has independent timeout configuration. Its instance-local single-flight cache and expiry
margin are unchanged. A valid cached token permits operation during an Auth outage; without
one the request fails safely. Single-flight monitor contention is not a separate request deadline.
Timeouts bound individual exchanges, not a global deadline across waiting/cache refresh/retry.

The Pricing-only Resilience4j core breaker avoids a Spring Boot version-specific starter/AOP.
It uses a count-based window, opens after configured failure rate/minimum calls, and permits
one half-open probe on the next call after the wait. Transport/5xx failures count; HTTP 4xx
and Auth acquisition QuoteExceptions are ignored. The first 401 refresh is included in the
same logical call. Open calls reject before token acquisition or HTTP and map to existing safe
503. HTTP error responses retain existing 502 mapping; unavailable/timeout paths use 503.
No premium fallback exists. A malformed success snapshot still fails validation safely.

There is no new transport/5xx retry. Only Pricing's existing one-time 401 invalidation and
reacquisition remains. A second 401 stops; 400/403/business errors are never retried.
Neither Auth nor Kafka gets a new circuit breaker. The circuit does not affect retrieval APIs.

Quote still calls remote services before entering QuotePersistenceService's transaction.
Failure creates no quote or outbox; success persists both atomically. Kafka waits remain
outside database transactions. Circuit state/counters are per-instance, not cluster-wide.

## Operations and validation

1. Inspect readiness and liveness separately when a service reports unhealthy.
2. Compare quote failures with pricing latency/errors and circuit state (filter with
   `?tag=state:open`). Check Auth health if Pricing call errors occur with a closed breaker.
3. During a Pricing outage, expect initial failures, then fast safe failures; restore Pricing,
   wait the open interval and submit a probe. Never manually fabricate a premium.
4. During Kafka outages, inspect outbox failure counts and cached pending age/count. Restore
   Kafka and observe published and processed counters. Existing idempotency absorbs duplicates.
5. Inspect retry/DLQ safe logs by topic/partition/offset and event ID. A DLQ acknowledgment
   is recovery of the source offset, not successful business processing or automatic replay.

Run `./mvnw -B test`, the existing `postgres-it` and `kafka-it` profiles separately, then package
and run `python3 infrastructure/validation/phase26-resilience-e2e.py`. The harness starts all
six packaged services with disposable PostgreSQL/Kafka, ephemeral signing keys and credentials,
checks outage/recovery, database atomicity, health/security/metrics, correlation and redaction,
and removes only its own containers/processes. See [validation evidence](../phase-26-validation.md).

No tracing collectors, monitoring servers, frontend changes, schema tuning or Phase 27 work.
Resilience4j behavior follows its [core circuit-breaker documentation](https://resilience4j.readme.io/docs/circuitbreaker).
