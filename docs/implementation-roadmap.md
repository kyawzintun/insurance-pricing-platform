# Implementation Roadmap

Status: Phases 0 through 14 complete; Phase 15 not started.

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

Not started.

## Later Phases

Detailed phase sequencing is pending approved planning notes. Application implementation will proceed incrementally through feature specifications. Transactional Outbox publishing is deferred; Phase 3 provides only its tables.
