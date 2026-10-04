# Implementation Roadmap

Status: Phases 0 through 4 complete; Phase 5 not started.

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

Not started. Gateway routing and distributed JWT validation remain deferred.

## Later Phases

Detailed phase sequencing is pending approved planning notes. Application implementation will proceed incrementally through feature specifications. Transactional Outbox publishing is deferred; Phase 3 provides only its tables.
