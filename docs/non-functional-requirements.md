# Non-Functional Requirements and Evidence

Implemented engineering boundaries include service-owned databases, independent authorization, durable event intent, idempotent local side effects, bounded HTTP waits/retries, safe errors/logs and observable dependency failures. Real PostgreSQL/Kafka integration tests cover transaction, concurrency and redelivery behavior; Angular tests cover session isolation and asynchronous state.

No production availability, throughput, compliance or disaster-recovery SLA is claimed. [PostgreSQL evidence](phase-27-postgres-performance-validation.md) measures SQL plans on local disposable datasets, not system capacity. [Security](security.md), [testing](testing.md) and [tradeoffs](engineering-tradeoffs.md) state operational limits and scope explicitly.
