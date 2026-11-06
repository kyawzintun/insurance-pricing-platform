# Project Context

The Insurance Pricing Platform is a completed learning and portfolio project for educational car-insurance quotes. It demonstrates Angular full-stack architecture, Spring Boot microservices, PostgreSQL ownership/performance, Kafka reliability and user/service security in a deliberately small domain. It is not actuarial pricing or a production insurance deployment.

CUSTOMER workflows cover registration, rotating-cookie sessions and quote creation/history/detail. ADMIN has Audit and Operations UI pages plus pricing/customer administration APIs. Notification persists simulated records. All planned implementation work through PostgreSQL tuning is complete, including the SignalStore migration and portfolio documentation.

The monorepo preserves specification-first, incremental implementation history. Phase specifications and validation reports remain evidence of their own revision, including deliberately intermediate designs. Current entry points are the [README](../README.md), [architecture](architecture.md), [security](security.md), [local setup](local-development.md) and [completed roadmap](implementation-roadmap.md).
