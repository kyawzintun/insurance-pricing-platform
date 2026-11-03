# Implementation Roadmap

## Phase 25 — Service-to-Service Authentication

Quote -> Pricing uses direct HTTP with a short-lived Auth-issued RS256 service JWT.
Quote authenticates to Auth with configured client credentials, caches tokens in
memory with single-flight acquisition, and retries once after Pricing 401. Pricing
requires quote-service identity, pricing:calculate scope and pricing-service audience;
CUSTOMER/ADMIN user tokens cannot call internal calculation, and service tokens cannot
use admin APIs. Secret distribution is limited to Auth/Quote; RSA private signing
remains Auth-only. User refresh, events and transaction semantics remain unchanged.
See [specification](specs/phase-25-service-authentication.md) and
[validation](phase-25-validation.md). Phase 26 implementation is described below. Earlier sections
record historical phase behavior and are superseded by this Phase 25 boundary.


Status: Phases 0 through 27 implemented and validated. Phase 27 is Centralized Operations Dashboard; the next focused phase has not started.

## Phase 0 — Repository and Project Foundation

Completed. Prepared the monorepo directories, root README, ignore rules, planning documents, ADR and specification folders, and empty Compose placeholder.

No business logic, Spring Boot applications, Angular code, Kafka or PostgreSQL configuration, Flyway migrations, Docker services, CI/CD, Kubernetes, Terraform, cloud deployment, or business tests belong in this phase.

## Phase 1 — Local Infrastructure

Completed. Added one PostgreSQL container with five logical databases, one Kafka KRaft broker, automated creation of six topics, persistent volumes, health checks, and developer instructions. Startup, database/table checks, topic configuration, repeat initialization, host connectivity, CLI messaging, and volume persistence checks passed. See [validation results](../infrastructure/phase-1-validation.md).

## Phase 2 — Spring Boot Service Skeletons

Completed. Six Spring Boot 4 Maven applications build and start independently, with minimal health endpoints and local environment configuration. The five database services connect to their own databases. No business functionality exists. See [validation results](phase-2-validation.md).

## Phase 3 — Database Foundation and Flyway

Completed. Fifteen service-owned Flyway migrations create the five database schemas and educational vehicle/pricing seeds. Live migration, restart, schema, seed, and Kafka-safety checks passed. See [validation results](phase-3-validation.md).

## Phase 4 — Authentication Basics

Completed. Auth Service supports normalized-email registration, BCrypt login, CUSTOMER/ADMIN roles, ACTIVE/DISABLED handling, and configurable HS256 access tokens. No refresh tokens. See [validation](phase-4-validation.md).

## Phase 5 — API Gateway and JWT Validation

Completed. Gateway routes Auth requests, validates HS256 JWTs, and supplies minimal CORS/correlation handling. Downstream JWT validation remains deferred. See [validation](phase-5-validation.md).

## Phase 6 — Pricing Service MVP

Completed. Internal read-only premium calculation uses existing database rules, deterministic selection/order, BigDecimal rounding, safe validation, and a snapshot breakdown. No admin or Quote integration. See [specification](specs/phase-6-pricing-service-mvp.md) and [validation](phase-6-validation.md).

## Phase 7 — Pricing Administration

Completed. ADMIN rule list/get/create/update/enable/disable through Gateway, independent Pricing JWT authorization, bounded pagination/filtering, shared rule validation, and optimistic locking. See [specification](specs/phase-7-pricing-administration.md) and [validation](phase-7-validation.md).

## Phase 8 — Quote Service MVP

Implemented CUSTOMER-only quote creation through Gateway, active vehicle-reference validation, direct synchronous Pricing REST, and atomic driver/vehicle/pricing snapshots with 30-day expiration. No schema changes or messaging. Automated/direct-service validation passed in Phase 8; the pending new-Gateway creation check passed during [Phase 9 validation](phase-9-validation.md) on temporary ports. See the historical [Phase 8 report](phase-8-validation.md). See [specification](specs/phase-8-quote-service-mvp.md).

## Phase 9 — Quote Retrieval and Ownership

Completed. CUSTOMER ownership-scoped detail/list, ADMIN access, stable bounded pagination, historical snapshot mapping, and read-only bulk loading. Normal tests, disposable PostgreSQL query-count tests, and live Gateway ownership validation passed. See [specification](specs/phase-9-quote-retrieval-and-ownership.md) and [validation](phase-9-validation.md).

## Phase 10 — Angular Foundation

Completed. Standalone Angular registration/login, protected dashboard, local Signals, Reactive Forms, shared NgRx authentication, Gateway-only HTTP, Bearer interceptor, and local logout. Memory-only sessions intentionally end on reload. See [specification](specs/phase-10-angular-foundation.md) and [validation](phase-10-validation.md).

## Phase 11 — Angular Quote Flow

Completed. CUSTOMER quote form, authenticated active vehicle catalog, creation/detail snapshots and paginated history through Gateway; quote NgRx workflows with local catalog/breakdown Signals. No messaging. See [specification](specs/phase-11-angular-quote-flow.md) and [validation](phase-11-validation.md).

## Phase 12 — Basic QuotePriced Kafka Event

Completed. Quote Service directly publishes a version 1 QuotePriced envelope after DB commit, keyed by quote ID on the existing quote topic. Producer failures do not roll back a committed quote. No outbox/consumers/retry-DLQ behavior; the dual-write gap remains until Phase 16. See [contract](quote-priced-event.md) and [validation](phase-12-validation.md).

## Phase 13 — Notification Consumer

Completed. Dedicated group consumes QuotePriced v1, validates its existing envelope, and persists simulated notifications before RECORD offset commits. Failures stop the consumer; event ID uniqueness remains visible without idempotency. See [specification](specs/phase-13-notification-consumer.md) and [validation](phase-13-validation.md).

## Phase 14 — Audit Consumer

Completed. Independent audit-service group consumes the unchanged QuotePriced v1 contract, writes compact JSONB audit records and commits progress after DB persistence. No idempotency/retry/DLQ. See [specification](specs/phase-14-audit-consumer.md) and [validation](phase-14-validation.md).

## Phase 15 — Idempotent Consumers

Completed. Notification and Audit atomically claim local processed-event markers and persist business rows, treating duplicates as success. Includes concurrent processing, rollback, lazy legacy compatibility and true Kafka redelivery validation. See [specification](specs/phase-15-idempotent-consumers.md) and [validation](phase-15-validation.md).

## Phase 16 — Transactional Outbox

Completed. Quote graph and full QuotePriced envelope commit atomically; bounded scheduled dispatch marks publication only after Kafka acknowledgment, with stable event identity across duplicates/recovery. See [design](transactional-outbox.md), [specification](specs/phase-16-transactional-outbox.md), and [validation](phase-16-validation.md).

## Phase 17 — Retry and DLQ

Completed. Notification/Audit use bounded fixed-backoff retries, non-retryable validation failures, and acknowledged publication of exact original bytes to the existing shared DLQ. Source progress is retained on DLQ failure; groups remain independent. See [policy](retry-and-dlq.md), [specification](specs/phase-17-retry-and-dlq.md), and [validation](phase-17-validation.md).

## Phase 18 — Quote Expiration

Implemented. UTC eligibility (`PRICED` and `expires_at <= now`), bounded PostgreSQL
row locking, atomic status/outbox commits, and QuoteExpired v1 reuse the existing
publisher, consumer idempotency, retries and DLQ. Notification creates QUOTE_EXPIRED;
Audit records SYSTEM with null actor ID. See the [specification](specs/phase-18-quote-expiration.md)
and [validation](phase-18-validation.md).

## Phase 19 — Auth/User Events

Implemented: transactional UserRegistered v1 outbox, Auth publisher, idempotent Notification/Audit user consumers, and existing retry/user DLQ integration. No historical backfill or admin status events. See [specification](specs/phase-19-auth-user-events.md), [contract](user-registered-event.md), and [validation](phase-19-validation.md).

## Phase 20 — Pricing Events

Implemented and validated: PricingRuleCreated/Updated/Enabled/Disabled v1 use
atomic pricing-rule/outbox transactions, authenticated ADMIN attribution, the
established advisory-lock publisher, and Audit idempotency/retry/pricing DLQ.
Calculation and Notification are unchanged; no historical backfill. See
[specification](specs/phase-20-pricing-events.md), [contracts](pricing-rule-events.md),
and [validation](phase-20-validation.md). Phase 21 has not been started.

## Phase 21 — Admin Customer Management

Implemented and validated: independent Gateway/Auth ADMIN authorization, CUSTOMER-only
safe paginated list/detail, idempotent enable/disable under PostgreSQL row locks,
atomic UserDisabled/UserEnabled Auth outbox intents, ADMIN/User Audit processing and
intentional Notification skips. Login status is enforced; existing issued JWTs are
not revoked. No migration or historical backfill. See [specification](specs/phase-21-admin-customer-management.md),
[API/security contract](admin-customer-management.md), [events](user-status-events.md)
and [validation](phase-21-validation.md). Phase 22 is documented below.

## Phase 22 — Audit UI

Completed. ADMIN-only Angular audit list/detail routes and navigation use Reactive Forms, local Signals and shared NgRx state. Gateway and Audit independently enforce ADMIN JWT authorization. Read-only API supports bounded server pagination, exact event/actor/entity/producer/correlation/eventId filters, UTC occurrence-time ranges and deterministic ordering. Metadata is returned only in detail and rendered as escaped JSON text. Existing indexes, consumer/event/outbox/retry behavior and immutable audit history are preserved. See [specification](specs/phase-22-audit-ui.md) and [validation](phase-22-validation.md). Phase 23 adds refresh tokens; see its specification.

### Phase 23 — Refresh Tokens

Implemented opaque rotating tokens, hash storage, HttpOnly cookies, idempotent logout,
disabled-user rejection, atomic PostgreSQL concurrency, Angular restoration and shared
refresh retries. See [specification](specs/phase-23-refresh-tokens.md) and
[validation](phase-23-validation.md). Phase 24 replaces symmetric access JWTs; refresh behavior is unchanged.

### Phase 24 — Asymmetric JWT

Completed. Auth signs RS256; Gateway/Auth/Quote/Pricing/Audit independently verify
using only public keys. Explicit PEM resources, startup pair checks, deterministic
DEV/test fixtures, algorithm-confusion rejection and disposable cross-service validation.
No migration or refresh redesign. See [specification](specs/phase-24-asymmetric-jwt.md)
and [validation](phase-24-validation.md).

### Phase 25 — Service-to-Service Authentication

Completed. Auth machine credentials issue RS256 service tokens; Quote caches and forwards them directly to Pricing. Service identity, scope and audience enforcement, single-flight acquisition, controlled 401 retry, outage safety, PostgreSQL regressions and disposable E2E are validated. See [specification](specs/phase-25-service-authentication.md) and [validation](phase-25-validation.md).

## Phase 26 — Observability & Resilience

Application logs now carry service/correlation context; all six services expose safe health,
liveness/readiness and Actuator metrics. Quote uses independent Auth/Pricing HTTP timeouts and
a Pricing-only circuit breaker, retaining the single 401 refresh and atomic quote/outbox commit.
No fallback premium, transport retry, event/security contract changes or Phase 27 work.
Outbox pending/age samples and publish/consumer outcome counters add operational visibility.
Metrics require ADMIN except Notification's internal numeric endpoint; keep that port private.
See [specification](specs/phase-26-observability-resilience.md) and
[validation](phase-26-validation.md) for configuration, readiness meaning and limits.

## Phase 27 — Centralized Operations Dashboard

ADMIN can open `/admin/operations`. Angular calls only Gateway's
`GET /api/v1/admin/operations`; a focused Gateway component concurrently collects the six
services' existing Phase 26 health/readiness and curated metrics. Gateway reads its own health
locally. Per-service failure preserves HTTP 200 and the other cards; overall becomes DEGRADED.
The fixed DTO excludes internal URLs, raw Actuator data and credentials. ADMIN authorization
is enforced by Gateway; its existing JWT is relayed only to trusted protected metric endpoints.
Collection has a configurable 1000 ms deadline per exchange and bounded response bodies.
Manual refresh and pausable 15-second refresh use NgRx state and local Signals.
Pricing circuit/latency/errors reuse Quote-owned metrics. Counters reset at restart; no
history, Prometheus/Grafana stack, database performance work or next phase is included.
See [specification and configuration](specs/phase-27-centralized-operations.md) and
[validation](phase-27-validation.md). This supersedes the old Phase 27 performance placeholder.
