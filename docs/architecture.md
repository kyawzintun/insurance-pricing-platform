# Architecture

## Phase 24 — RS256 access JWTs

Auth now signs access JWTs with a private RSA key using **RS256 only**. Auth's admin
APIs, Gateway, Quote, Pricing and Audit independently verify with the public key;
verifier services receive no private-key configuration. Existing role/ownership,
issuer, expiry, subject, error and correlation behavior remains unchanged. Opaque
Phase 23 refresh cookies and rotation are unchanged. Old HS256 access tokens are
rejected; refresh/login obtains RS256 tokens. No JWKS, key rotation or Phase 25 work.

Configure `AUTH_JWT_PUBLIC_KEY=file:/path/jwt-public-key.pem` in all five services and
`AUTH_JWT_PRIVATE_KEY=file:/path/jwt-private-key.pem` **only in Auth**. Keys are RSA PEM
(at least 2048 bits), PKCS#8 private and X.509 SubjectPublicKeyInfo public. Non-local
profiles require external files. Default `local` uses publicly known **development-only**
fixtures, never suitable for production. `AUTH_JWT_ISSUER` and `AUTH_JWT_ACCESS_TOKEN_TTL`
retain existing defaults. `AUTH_JWT_SECRET` is no longer supported.

See [Phase 24 specification](specs/phase-24-asymmetric-jwt.md) and [validation](phase-24-validation.md) for setup,
startup checks, private-key isolation, rollout and validation evidence.

> Earlier phase sections below record historical behavior; Phase 24 configuration above supersedes symmetric-key instructions.


Status: Implemented through Phase 21; earlier phase sections record historical milestones.

## Approved Constraints

1. Use a monorepo.
2. Use an Angular frontend with NgRx.
3. Use an API Gateway plus five backend services: Auth Service, Quote Service, Pricing Service, Notification Service, and Audit Service.
4. Use PostgreSQL for all backend services.
5. Use Kafka for asynchronous domain events.
6. Use REST for synchronous communication when an immediate response is needed.
7. Each microservice owns its own logical database.
8. No cross-service database access.
9. Introduce Transactional Outbox in a later phase.
10. Implement incrementally, phase by phase.

Spring Boot 4 and Spring Security are planned backend technologies. No additional microservices are in scope; do not add Vehicle, Customer, Policy, Payment, Claims, or Underwriter services.

## Repository Boundaries

`frontend/` holds the Angular application. `services/` reserves directories for the approved gateway and services. `infrastructure/` holds shared runtime/deployment support such as Docker, Kafka setup, and PostgreSQL initialization.

Future Flyway migrations belong inside the owning Spring Boot service, never in `infrastructure/`. Kubernetes and Terraform are outside Phase 0 and are not configured.

No service scaffolding, runtime infrastructure, database schemas, or communication contracts are implemented in Phase 0. Detailed topology, API contracts, and deployment choices remain pending.

## Phase 1 — Local Infrastructure

One local PostgreSQL container hosts five logical service databases. One Kafka broker runs in combined broker/controller KRaft mode, without ZooKeeper. A one-shot Compose initialization job creates the approved domain topics after Kafka passes its health check. Named volumes persist both systems across container recreation.

Host applications use `localhost:5432` and `localhost:9092` by default; future containers on the Compose network use `postgres:5432` and `kafka:19092`. No application services are containerized yet. Local setup details are in [infrastructure/README.md](../infrastructure/README.md).

## Phase 2 — Spring Boot Skeletons

Six independent Java 25 / Spring Boot 4.1.1 applications now use a root Maven parent/aggregator and Maven Wrapper. Gateway uses the compatible Spring Cloud 2025.1.3 release train and Gateway Server WebFlux 5.0.3. The gateway has no datasource or routes; the five backend skeletons use their own PostgreSQL databases. No shared Java business code is introduced. See [service guidance](../services/README.md) for dependencies, packages, ports, and official compatibility sources.

## Phase 3 — Schema Ownership

Each database service owns its Flyway files under its own `src/main/resources/db/migration/`. Gateway remains database-free. Only service-local foreign keys are used; cross-service identifiers remain plain references. The existing Maven build and Docker infrastructure are unchanged. Outbox and processed-event schemas prepare later messaging work without implementing it.

## Phase 5 — Gateway Authentication

Gateway now routes `/api/v1/auth/**` to configurable Auth Service URL (default localhost:8081). POST register/login and GET health/info are public; other requests require a validated HS256 JWT. The local profile shares Auth's public development key; outside local both services require a configured secret. Subject and roles are parsed, with no business role authorization. Authorization is forwarded unchanged; downstream token validation remains future work. Minimal configurable CORS and bounded correlation IDs are implemented. No business routes, messaging, schema changes, or discovery were added. See [Gateway details](../services/api-gateway/README.md) and [validation](phase-5-validation.md). Earlier phase sections describe historical milestones; current status is Phase 15 complete.

## Phase 6 — Internal Pricing Calculation

Pricing Service exposes POST `/internal/v1/pricing/calculate` directly on port 8083. Its conventional controller/service/repository/DTO layers read the existing pricing_rules table, derive ages with a UTC Clock, and return a deterministic THB breakdown with rule IDs/versions. The calculation is read-only; schemas, seeds, outbox, and Kafka behavior are unchanged. The endpoint is temporarily unauthenticated, stays off Gateway, and is intended for private/internal access only. Service-to-service authentication is deferred to Phase 25; this is not production-secure. No admin CRUD or Quote integration was added. See [Pricing semantics and limitations](../services/pricing-service/README.md).

## Phase 7 — Pricing Administration

Gateway routes only `/api/v1/admin/pricing/**` to configurable Pricing URL (default localhost:8083). Gateway and Pricing independently verify HS256 JWTs and require ADMIN; no user identity headers are trusted. Pricing owns rule list/get/create/full update/enable/disable APIs with shared Phase 6 validation and existing @Version optimistic locking. Writes affect only pricing_rules. No schemas, seeds, Kafka/outbox behavior, or unrelated services changed. Internal calculation remains temporarily unauthenticated directly on Pricing, not routed by Gateway; service-to-service authentication is still deferred to Phase 25. No Angular admin UI or Quote integration. See [admin guide](../services/pricing-service/README.md) and [validation](phase-7-validation.md).

## Phase 8 — Quote Service MVP

CUSTOMER can POST `/api/v1/quotes` through Gateway. Quote independently validates HS256 signature, expiration, issuer, UUID subject, and roles; customer_id comes only from sub. ADMIN-only cannot create quotes. Gateway forwards the Bearer token without trusted identity headers. Quote validates input and active, matching brand/model references in quote_db and snapshots their names.

Quote synchronously calls Pricing directly with only its seven calculation inputs, no customer JWT/identity, and bounded connection/read timeouts. **Quote → Pricing uses internal REST without service credentials; service-to-service authentication remains deferred to Phase 25.** After a usable response, a separate transaction stores the complete PRICED aggregate and 30-day expiration. Pricing failure creates no partial quote or FAILED row. Existing premiums and names are historical snapshots. No cross-service database reads, schema/seed changes, outbox writes, or Kafka messages were introduced.

No retrieval/history, ownership retrieval rules, editing, repricing, or expiration scheduler exists yet. See [Quote specification](specs/phase-8-quote-service-mvp.md), [service guide](../services/quote-service/README.md), and [validation](phase-8-validation.md). Phase 9 is not started.

## Phase 9 — Quote Retrieval and Ownership

GET `/api/v1/quotes/{id}` and GET `/api/v1/quotes` return stored snapshots. Quote independently requires CUSTOMER/ADMIN. CUSTOMER detail/list queries include JWT-sub ownership; another owner's quote and a missing quote return identical 404s. ADMIN can retrieve/list all owners. No customerId override, optional filters, or Gateway-only authorization. POST remains CUSTOMER-only.

Pagination defaults to page 0 / size 20 (maximum 100), fixed creation-descending/ID-descending order. Read-only transactions map stored data; scalar ID pagination followed by an unpaged snapshot entity graph avoids collection-fetch pagination and N+1 queries. No Pricing/current catalog dependency, status transition, migration, outbox write, or Kafka event. Live validation used temporary application ports and cleaned up its processes/data. See [Phase 9 validation](phase-9-validation.md) and [Quote guide](../services/quote-service/README.md). Phase 10 is documented below.

## Phase 10 — Angular Foundation

Standalone Angular provides registration/login and a protected dashboard placeholder. Reactive Forms own values and validation; component Signals own loading, safe errors, and password visibility; NgRx Store owns token, user ID, roles, expiry, and authentication state. Effects navigate after login/logout. Credentials never enter NgRx actions/state.

All HTTP goes through the configurable Gateway base URL. Bearer attachment is limited to its API paths and excludes login/register. Protected-request 401 now uses the Phase 23 shared refresh and retry flow. Guards and decoded JWT claims support UI only; backend authorization remains authoritative. Logout clears memory and revokes the refresh cookie. Browser reload attempts session restoration. No signing secrets or token persistence are included. Refresh sessions are implemented in Phase 23. Backend production code, schemas, and infrastructure remain unchanged. See [frontend guide](../frontend/README.md) and [validation](phase-10-validation.md).

## Phase 11 — Customer Quote Frontend

CUSTOMER-only frontend quote routes provide creation, paginated owned history and stored detail. Reactive Forms own inputs; local Signals own catalog/expansion state; NgRx Effects/Store own shared quote results, pagination and request outcomes. Session changes clear quote state and cancel pending responses. All calls use Gateway with the existing Bearer interceptor; backend ownership remains authoritative. No customerId is supplied by Angular.

Quote-owned active vehicle references are exposed as read-only id/name DTOs through authenticated Gateway routes. CUSTOMER and ADMIN tokens may read the reference APIs; the frontend workflow is CUSTOMER-only. Missing/inactive brands have the same 404. No cross-service database reads, migrations, new tables, outbox writes or messaging. Detail renders historical snapshots without current Pricing/catalog requests. Date-only birth dates preserve their calendar day. See [Phase 11 validation](phase-11-validation.md).

## Phase 12 — QuotePriced direct producer

Quote Service registers an explicit DTO event inside persistence and sends it to the existing quote topic only after successful commit. The key is quote ID; metadata captures UTC occurrence time and the same safe correlation-ID convention as Gateway. The compact payload excludes credentials and personal driver details. Safe producer failure logging does not change HTTP 201 or stored PRICED state. No cross-service schema changes, consumers, outbox writes or retry/DLQ processing. Direct publishing has an intentional dual-write failure window until Phase 16. See [event contract](quote-priced-event.md) and [validation](phase-12-validation.md).

## Phase 13 — Notification Consumer

Notification Service consumes QuotePriced v1 from `insurance.quote.events` in its dedicated configurable `notification-service` group. It writes only simulated CREATED notifications in its own database, with no external HTTP/provider calls. Consumer-owned DTOs match the unchanged Phase 12 wire contract. Earliest offset reset deliberately supports historical learning records; committed offsets take precedence on restart. RECORD acknowledgment follows the local database transaction commit. Invalid supported events, unsupported versions and persistence failures stop consumption without skipping the failed offset; unknown event types are intentionally logged/skipped. Existing UNIQUE(event_id) is preserved but is not full idempotency. No processed_events, retry/DLQ, Audit consumer or outbox logic is implemented. See [Notification guide](../services/notification-service/README.md), [specification](specs/phase-13-notification-consumer.md), and [validation](phase-13-validation.md).

## Phase 14 — Independent Audit consumer

Audit now consumes the unchanged QuotePriced v1 envelope using its own configurable `audit-service` group, separate from `notification-service`. Both groups independently receive the same records. Audit writes only audit_records, with CUSTOMER/customerId attribution, Quote/quoteId entity identity, preserved correlation/occurrence data, and compact allowlisted JSONB metadata. RECORD offset commits follow local DB commit; unsupported types are skipped and invalid supported events/database failures stop the container. Earliest supports historical learning events. UNIQUE(event_id) remains visible without processed_events/idempotency, retry/DLQ or Outbox logic. A necessary Audit-only V3 widens correlation_id to 128 to match the existing producer contract, preserving V1/V2 checksums. See [Audit guide](../services/audit-service/README.md), [specification](specs/phase-14-audit-consumer.md) and [validation](phase-14-validation.md).

## Phase 15 — Idempotent Notification and Audit consumers

Both consumers now use their own existing processed_events tables, keyed by eventId and stable logical consumer name (notification-service / audit-service). An atomic ON CONFLICT insert claims the event inside the same local JPA transaction as its business write. Conflict returns DUPLICATE successfully, without rewriting the business row or original processed_at; RECORD acknowledgment may then advance the offset. Concurrent attempts are serialized by PostgreSQL uniqueness. A replay of a pre-Phase-15 business row lazily establishes its missing marker only after the atomic claim, without bulk backfill or rewriting the row. Genuine failures preserve stop/uncommitted-offset behavior. No schema, producer contract, group/topic, retry/DLQ or Outbox changes. See [specification](specs/phase-15-idempotent-consumers.md) and [validation](phase-15-validation.md).

## Phase 16 — Quote Transactional Outbox

Quote creation now stores the aggregate and a complete QuotePriced v1 JSONB envelope in the same transaction. The direct after-commit publisher is removed. HTTP 201 means durable quote/event intent; scheduled polling publishes later with the same event ID, correlation, occurrence time, topic and quote UUID key. An autocommit connection with a shared PostgreSQL session advisory lock excludes competing dispatchers without holding a transaction during Kafka I/O. Acknowledged sends become PUBLISHED; failed attempts remain PENDING and increment retry_count. Duplicate publication remains possible and Phase 15 consumers handle it unchanged.

Quote V6 widens only outbox correlation_id to 128 to match the existing contract; no other schema or seed changes, historical backfill, Auth/Pricing outbox, or Phase 17 retry/DLQ policy. The preceding phase sections describe historical milestones; this section supersedes their producer limitations. See [design and operational limits](transactional-outbox.md), [specification](specs/phase-16-transactional-outbox.md), and [validation](phase-16-validation.md).

## Phase 17 — Consumer retry and DLQ

Notification and Audit now use Spring Kafka DefaultErrorHandler with configurable fixed backoff (default three total attempts, 1000 ms). Invalid supported events go directly to the existing insurance.quote.events.dlq; unknown event types still skip and idempotent duplicates still succeed. Raw key/value bytes are preserved, with safe failure headers identifying the source coordinates and failing service/group. Source offsets advance only after business success or acknowledged DLQ publication; failed DLQ sends leave the source replayable. Independent group failure/recovery was verified with two actual packaged services in disposable infrastructure.

The QuotePriced contract, Quote outbox, processed-event semantics, schemas and topic topology are unchanged. Earlier phase stopping-handler descriptions are historical and superseded here. No replay consumer, retry topics, new business events or Phase 18 work. See [policy and limitations](retry-and-dlq.md), [specification](specs/phase-17-retry-and-dlq.md), and [validation](phase-17-validation.md).

## Phase 18 — Quote Expiration

Automatic PRICED → EXPIRED transitions use bounded PostgreSQL transactions and the existing outbox. Notification supports QUOTE_EXPIRED; Audit uses a SYSTEM actor. See the [Phase 18 specification](specs/phase-18-quote-expiration.md) for configuration, contract, concurrency and failure semantics. Phase 19 is outside this change.

## Phase 19 — Auth/User Events

Auth now persists UserRegistered v1 in its existing outbox during registration, then
publishes to insurance.user.events keyed by userId. Notification and Audit reuse
their idempotency and retry/DLQ paths with insurance.user.events.dlq. Auth V4 widens
outbox correlation_id to 128; consumer schemas need no migration. No historical
backfill or admin status events. See [contract and lifecycle](user-registered-event.md)
and [validation](phase-19-validation.md). Earlier phase sections are historical.

## Phase 20 — Pricing Events

Pricing admin create/update/enable/disable now commit operation-specific v1 events
in the existing outbox with the rule mutation. The service-local publisher sends to
insurance.pricing.events keyed by pricingRuleId; Audit records ADMIN/PricingRule
using existing idempotency and pricing retry/DLQ handling. Pricing V4 widens only
outbox correlation_id to 128. Calculation emits no event; Notification is unchanged.
See [contracts and lifecycle](pricing-rule-events.md), [specification](specs/phase-20-pricing-events.md),
and [validation](phase-20-validation.md). No historical backfill or Phase 21 work.

## Phase 21 — Admin Customer Management

Gateway and Auth independently authorize ADMIN customer list/detail/enable/disable.
PostgreSQL customer row locks serialize real status transitions with existing Auth
outbox inserts. Idempotent repeats create no intent. UserDisabled/UserEnabled v1
reuse the user topic/key, publisher and Audit idempotency/retry/user DLQ. Audit uses
ADMIN attribution; Notification intentionally skips status events. No migration,
backfill or token revocation: already-issued JWTs may still authorize Quote access
until expiry. See [API/security contract](admin-customer-management.md),
[event contract](user-status-events.md) and [validation](phase-21-validation.md).
Phase 22 is not implemented.

## Phase 22 — Audit reads

Angular ADMIN Audit UI → Gateway → Audit Service → audit_records → server-side filters + pagination. Both services enforce ADMIN independently. The API uses existing JPA/PostgreSQL and event-time indexes, compact list DTOs and stored allowlisted JSON detail. Existing Kafka ingestion and idempotency are unchanged. See [specification](specs/phase-22-audit-ui.md).

## Phase 23 — Refresh tokens

The current session flow uses short-lived access JWTs in Angular memory and rotating
opaque refresh tokens in HttpOnly cookies, with SHA-256 digests in the existing Auth
table. POST refresh/logout are public through Gateway. Secure defaults to true
(local HTTP profile false); SameSite=Lax, auth-only path, TTL 7 days, all configurable.
Gateway permits credentials only for configured exact origins. Angular restores the
session before initial navigation and shares one refresh across concurrent 401s.
Logout revokes the cookie; disabled-user refresh is rejected and revoked. PostgreSQL
row locks serialize rotation, with rollback on replacement failure. Access JWTs
remain valid until expiry. No token events or Phase 24 changes are included.

See [Phase 23 specification](specs/phase-23-refresh-tokens.md) for configuration,
CSRF assumptions, queue behavior, transaction guarantees and deferred cleanup/family tracking.
Earlier phase descriptions below/above are historical where superseded by Phase 23.
