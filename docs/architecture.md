# Architecture

Status: Approved high-level decisions recorded from the Phase 0 request; detailed design pending.

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

`frontend/` holds the future Angular application. `services/` reserves directories for the approved gateway and services. `infrastructure/` holds shared runtime/deployment support such as Docker, Kafka setup, and PostgreSQL initialization.

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

Gateway now routes `/api/v1/auth/**` to configurable Auth Service URL (default localhost:8081). POST register/login and GET health/info are public; other requests require a validated HS256 JWT. The local profile shares Auth's public development key; outside local both services require a configured secret. Subject and roles are parsed, with no business role authorization. Authorization is forwarded unchanged; downstream token validation remains future work. Minimal configurable CORS and bounded correlation IDs are implemented. No business routes, messaging, schema changes, or discovery were added. See [Gateway details](../services/api-gateway/README.md) and [validation](phase-5-validation.md). Earlier phase sections describe historical milestones; current status is Phase 9 complete; earlier sections describe historical milestones.

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

Pagination defaults to page 0 / size 20 (maximum 100), fixed creation-descending/ID-descending order. Read-only transactions map stored data; scalar ID pagination followed by an unpaged snapshot entity graph avoids collection-fetch pagination and N+1 queries. No Pricing/current catalog dependency, status transition, migration, outbox write, or Kafka event. Live validation used temporary application ports and cleaned up its processes/data. See [Phase 9 validation](phase-9-validation.md) and [Quote guide](../services/quote-service/README.md). Phase 10 has not started.
