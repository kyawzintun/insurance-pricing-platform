# Phase 27 — Centralized Operations Dashboard

> Historical milestone record: scope, commands, counts and limitations below describe that revision. For the completed implementation and current run instructions, start with the [root README](../../README.md).

Phase 27 is a read-only aggregation/presentation layer over Phase 26. It adds no service,
business counters, database/history, event contract, mutation API or monitoring infrastructure.
The next project phase is not started. This phase supersedes the earlier roadmap placeholder
that called Phase 27 PostgreSQL Performance; database performance work is outside this scope.

```text
Angular ADMIN
     ↓
GET /api/v1/admin/operations
     ↓
Gateway Operations Aggregator
     ↓
 ┌─────┬──────┬───────┬─────────┬──────────────┬───────┐
 GW    Auth   Quote   Pricing   Notification    Audit
 └─────┴──────┴───────┴─────────┴──────────────┴───────┘
     ↓
fixed health + metric summary
```

## API and security

`GET /api/v1/admin/operations` is handled locally in Gateway, not routed to another service.
Anonymous requests receive safe 401; CUSTOMER receives 403; ADMIN receives the overview.
Existing admin/customer/auth/quote routes and independent service authorization are unchanged.
Unknown operations children are not proxies and return 404 for ADMIN. The response is no-store.

The schema contains `overallStatus`, `checkedAt`, and exactly six `services` in stable order:
api-gateway, auth-service, quote-service, pricing-service, notification-service, audit-service.
Each service has `id`, `name`, `status`, `readiness`, `responseTimeMs`, `checkedAt`, `errorCode`
and an allowlisted `metrics` map. No URLs, credentials, raw Actuator JSON/tags, exception text,
environment, SQL details or payloads are included.

Example partial card:

```json
{
  "id": "pricing-service",
  "name": "Pricing",
  "status": "DOWN",
  "readiness": "UNKNOWN",
  "responseTimeMs": 12,
  "checkedAt": "2026-11-03T10:00:00Z",
  "errorCode": "CONNECTION_REFUSED",
  "metrics": {
    "outboxPending": null,
    "outboxPublished": null,
    "outboxFailed": null,
    "pricingCallErrors": 3,
    "pricingAvgLatencyMs": 82,
    "circuitBreakerState": "OPEN"
  }
}
```

Gateway forwards the already-validated ADMIN access JWT only to the fixed selected metrics
endpoints on Auth, Quote, Pricing and Audit, which already require ADMIN. It does not acquire
new service credentials or broaden Phase 25 service-token scopes. Health/readiness are public,
and Notification metrics need no JWT: neither receives a credential. The token is request-local,
not cached/logged/serialized. Internal origins must be trusted deployment configuration;
HTTP(S) origins only, without userinfo/path/query/fragment. Redirect following is disabled.
Keep backend ports on private networking as before; use HTTPS when required by deployment.

## Configuration and fan-out

Gateway reuses AUTH_SERVICE_URL, QUOTE_SERVICE_URL, PRICING_SERVICE_URL and AUDIT_SERVICE_URL.
NOTIFICATION_SERVICE_URL defaults to http://localhost:8084. These are server-only settings;
Angular continues to use only its existing public Gateway base URL. No service discovery added.

OPERATIONS_HEALTH_TIMEOUT_MS defaults to 1000 (allowed 50..10000). The five service cards are
subscribed concurrently. Within a card, health, readiness and selected metrics are also
concurrent. Every exchange has connect/response and total reactive deadlines. Each body is
limited to 16 KiB; paths, services and call counts are fixed. Overall collection time is bounded
approximately by the slowest deadline plus local scheduling/serialization overhead, not six
sequential timeouts. No retries or caching are added. responseTimeMs measures the whole card
collection including metrics, not just one health request.

Gateway self-health comes from its existing HealthEndpoint and readiness group locally, on
boundedElastic so blocking indicators do not run on Netty event-loop threads. It makes no HTTP
call to itself. Local health reads also have the configured deadline.

## Health, readiness and partial failures

UP maps to UP; DOWN/OUT_OF_SERVICE map to DOWN; unknown/invalid values map to UNKNOWN.
Readiness UP maps to READY; DOWN/OUT_OF_SERVICE to NOT_READY; unavailable/missing/invalid
readiness to UNKNOWN. A valid health 503 body is parsed so a real Actuator DOWN remains DOWN.
Transport failures produce health DOWN and a safe TIMEOUT, CONNECTION_REFUSED or
INVALID_RESPONSE code. Local indicator failures use LOCAL_HEALTH_UNAVAILABLE. Raw error text
never crosses the API. No readiness is inferred from health or frontend state.

Every remote failure is isolated. The endpoint still returns HTTP 200 with all six cards,
unavailable metrics null, and useful remaining health data. Overall status is UP only if every
card is UP and READY; otherwise DEGRADED. Missing metrics alone do not change service health.
Unexpected failure of the aggregator itself follows the existing safe Gateway error behavior.
There is no rule declaring the platform DOWN merely because some probes fail.

Readiness keeps the Phase 26 meaning: Gateway application state, DB-owning services' own DB,
and local consumer lifecycle for Notification/Audit. It does not establish broker reachability,
lag, partition assignment or end-to-end delivery. No new dependency health logic is invented.

## Curated metrics

| Display keys | Phase 26 source |
| --- | --- |
| quoteCreated, quoteFailed | Quote insurance.quote.created / failed |
| pricingCallErrors | Quote insurance.pricing.client.errors |
| pricingAvgLatencyMs | Quote insurance.pricing.client.duration TOTAL_TIME / COUNT × 1000 |
| circuitBreakerState | Quote insurance.pricing.circuit.state, filtered closed/open/half_open |
| outboxPending, outboxPublished, outboxFailed | Auth/Quote/Pricing insurance.outbox.pending / published / failed |
| processedEvents, duplicateEvents, dlqCount | Notification/Audit insurance.kafka.consumer.processed / duplicate / dlq |

The Pricing client is owned by Quote. The Pricing card explicitly reuses Quote's latency,
error and circuit telemetry rather than inventing Pricing-local counters. It remains visible
when Pricing is unreachable, if Quote is reachable. Circuit state comes from one-hot gauges,
never Angular calculation: exactly one active gauge maps to CLOSED/OPEN/HALF_OPEN, otherwise
null. Samples are not atomic across gauges; a concurrent transition can therefore show null.

Only finite nonnegative numeric measurements are returned. An absent metric (including one
not yet registered), denied request, parse failure, -1 unknown pending sample, timeout, or zero
count for average latency produces null. Null is displayed as Unavailable, never fabricated
zero. Existing real zero values remain zero. Gateway has no curated custom metrics and Auth
has no Phase 26 login/refresh/service-token failure counters: those are intentionally omitted.

Counters are instance-local runtime values and reset at restart. They are not historical
business totals. Outbox gauges reuse Phase 26 cached samples; dashboard requests never query
service databases or trigger a pending-row scan. There is no Prometheus/Grafana dependency.

## Angular

`/admin/operations` is lazy loaded behind the existing adminGuard. Only ADMIN sees Operations
navigation; Audit and customer/quote links remain unchanged. Backend authorization is final.
The API service performs one GET to Gateway. NgRx holds overview/loading/error; effects use
exhaustMap to avoid overlapping requests, cancel on page leave/session changes and reset data
on login/logout/failure as established by Audit. Signals own the local auto-refresh toggle and
computed healthy/ready count.

A responsive six-card grid displays textual health/readiness badges, collection latency,
checked time, safe probe errors and human-readable metrics. Platform summary shows status,
healthy-and-ready count and last successful refresh. Color supplements text. Angular
interpolation escapes values; only known metric keys are rendered. No raw JSON or HTML binding.

Refresh is manual and every 15 seconds while enabled. The checkbox pauses polling. Component
destruction unsubscribes the timer and cancels in-flight requests. Controls disable while
loading. Aggregator-level failures show a safe message and label any retained snapshot as old;
per-service failures remain in the six-card snapshot. No history, charts or mutation controls.

## Validation and limitations

Run Maven tests/package, Angular `npm test` and `npm run build`, then
`python3 infrastructure/validation/phase27-operations-e2e.py`. The harness uses disposable
PostgreSQL/Kafka and all six packaged apps, creates runtime quote activity and verifies ADMIN
access, metrics, Pricing outage/recovery and safe output. Optional PHASE27_BROWSER_CHECK=true
uses Gateway port 8080 and explicit ready/continue files for manual browser checkpoints with
the development frontend on port 4200. It is a validation-only option, not product code.
See [validation results](../phase-27-validation.md).

Limits: per-instance snapshots, sequential instants rather than distributed atomic snapshots,
no history or alerting, no cross-instance metric aggregation, no probes of brokers/lag beyond
Phase 26, and no cache/rate-limiter across many ADMIN sessions. Metric failure is represented
as null without a separate per-metric diagnostic code. The trusted internal origin configuration
must match actual instance ports; this is a static local topology, not a discovery platform.
