# Insurance Pricing Platform

A learning project for practicing Angular + NgRx, Spring Boot 4, Spring Security, Kafka, PostgreSQL, Docker, microservices, and specification-first AI-assisted SDLC.

The project focuses on a simple car-insurance pricing platform. It is not intended to implement realistic actuarial insurance pricing. The main learning targets are Spring Boot/microservices, Kafka, Angular + NgRx, PostgreSQL, and specification-first AI-assisted development.

## Current Project Status

**Current Phase: Phase 0 — Repository and Project Foundation**

Only repository structure and documentation are present. No application implementation has started yet. Implementation will proceed phase by phase.

## Main Users

- **CUSTOMER**
- **ADMIN**

## Planned Features

All features below are planned, not implemented:

- Customer registration and authentication
- Car insurance quote creation
- Database-driven pricing rules
- Quote pricing breakdown
- Admin pricing-rule management
- Quote ownership security
- Kafka-based notifications
- Kafka-based audit logging
- 30-day quote expiration

## Planned Architecture

- **Angular + NgRx** frontend
- **API Gateway**
- **Auth Service**
- **Quote Service**
- **Pricing Service**
- **Notification Service**
- **Audit Service**
- **Kafka** for asynchronous domain events
- **PostgreSQL** for all backend services

REST will be used for synchronous communication when an immediate response is needed. Each microservice will own its own logical database, with no cross-service database access. Transactional Outbox will be introduced in a later phase. These are planned components; no services or runtime configuration exist yet.

## Repository Structure

```text
frontend/                    Planned Angular + NgRx application
services/                    Planned gateway and five backend services
  api-gateway/
  auth-service/
  quote-service/
  pricing-service/
  notification-service/
  audit-service/
infrastructure/              Shared runtime/deployment support
  docker/
  kafka/
  postgres/
docs/                        Project planning documentation
  adr/                       Architecture Decision Records
  specs/                     Future implementation specifications
docker-compose.yml           Empty Compose placeholder
```

Empty runtime directories contain `.gitkeep` files so Git retains them. `infrastructure/` is reserved for shared Docker, Kafka, and PostgreSQL support. Future service-owned Flyway migrations must live in the owning Spring Boot service, never in `infrastructure/`.

Start with [project context](docs/project-context.md), [architecture](docs/architecture.md), and the [implementation roadmap](docs/implementation-roadmap.md). Detailed planning that has not yet been supplied is explicitly marked as pending.

## Future Local Development

In Phase 1, shared local infrastructure will be added. Later, the intended command is:

```bash
docker compose up -d
```

This command does **not** start infrastructure yet: the Compose file currently defines no services. Local setup instructions will be added alongside the infrastructure implementation.
