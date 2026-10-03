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
