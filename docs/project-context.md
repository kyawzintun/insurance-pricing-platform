# Project Context

Status: Approved high-level context recorded from the Phase 0 request; detailed planning pending.

Insurance Pricing Platform is a learning project for a simple car-insurance pricing platform, not realistic actuarial insurance pricing.

Planned technologies: Angular + NgRx, Spring Boot 4, Spring Security, Kafka, PostgreSQL, Docker, and microservices. The main learning targets are Spring Boot/microservices, Kafka, Angular + NgRx, PostgreSQL, and specification-first AI-assisted SDLC.

The main users are CUSTOMER and ADMIN. Work proceeds incrementally, phase by phase, in one monorepo. Phase 0 provides only repository and documentation foundations; no application implementation has started.

Detailed approved planning notes have not yet been supplied. Future specifications should capture agreed behavior before implementation; unresolved details must not be treated as approved requirements.

Phase 1 adds shared local PostgreSQL and Kafka infrastructure only. Application code remains unimplemented; Phase 2 has not started.

Phase 2 adds six startup-only Spring Boot 4 Maven applications. No business implementation exists. Phase 3 (Database Foundation and Flyway) has not started.

Phase 3 provides service-owned Flyway schemas and fixed educational seeds only. Phase 4 — Authentication Basics has not started.

Phase 4 implements Auth Service registration/login and JWT issuance only. Phase 5 — API Gateway and JWT Validation has not started.

## Phase 5 — Gateway Authentication

Gateway now routes `/api/v1/auth/**` to configurable Auth Service URL (default localhost:8081). POST register/login and GET health/info are public; other requests require a validated HS256 JWT. The local profile shares Auth's public development key; outside local both services require a configured secret. Subject and roles are parsed, with no business role authorization. Authorization is forwarded unchanged; downstream token validation remains future work. Minimal configurable CORS and bounded correlation IDs are implemented. No business routes, messaging, schema changes, or discovery were added. See [Gateway details](../services/api-gateway/README.md) and [validation](phase-5-validation.md). Earlier phase sections describe historical milestones; current status is Phase 7 complete, Phase 8 not started.

## Phase 6 — Internal Pricing Calculation

Pricing Service exposes POST `/internal/v1/pricing/calculate` directly on port 8083. Its conventional controller/service/repository/DTO layers read the existing pricing_rules table, derive ages with a UTC Clock, and return a deterministic THB breakdown with rule IDs/versions. The calculation is read-only; schemas, seeds, outbox, and Kafka behavior are unchanged. The endpoint is temporarily unauthenticated, stays off Gateway, and is intended for private/internal access only. Service-to-service authentication is deferred to Phase 25; this is not production-secure. No admin CRUD or Quote integration was added. See [Pricing semantics and limitations](../services/pricing-service/README.md).

## Phase 7 — Pricing Administration

Gateway routes only `/api/v1/admin/pricing/**` to configurable Pricing URL (default localhost:8083). Gateway and Pricing independently verify HS256 JWTs and require ADMIN; no user identity headers are trusted. Pricing owns rule list/get/create/full update/enable/disable APIs with shared Phase 6 validation and existing @Version optimistic locking. Writes affect only pricing_rules. No schemas, seeds, Kafka/outbox behavior, or unrelated services changed. Internal calculation remains temporarily unauthenticated directly on Pricing, not routed by Gateway; service-to-service authentication is still deferred to Phase 25. No Angular admin UI or Quote integration. See [admin guide](../services/pricing-service/README.md) and [validation](phase-7-validation.md).
