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
