# Engineering Tradeoffs and Intentional Scope

The completed project demonstrates distributed engineering in a small business domain. These are conscious portfolio boundaries, not required remaining implementation work.

| Decision | Reason and consequence |
| --- | --- |
| Microservices | Makes service ownership, network failures, machine identity and eventual consistency observable. A small real product could use a modular monolith with much lower operational cost. |
| Synchronous Quote → Pricing | Immediate premium is required before quote persistence. Availability depends on Auth for fresh service tokens and on Pricing; bounded waits/circuit breaking fail safely rather than inventing prices. |
| Kafka for side effects | Notification/Audit need not delay quote HTTP success. Readers accept eventual consistency, duplicate delivery and independent consumer progress. |
| Polling outbox, no CDC | Demonstrates atomic intent with familiar PostgreSQL/JDBC. Polling adds latency; a service-wide session advisory lock serializes dispatch and borrows a connection during publication. Transaction-pooling proxies are incompatible with this lock design. |
| No exactly-once claim | PostgreSQL commits, broker acknowledgment and offset commits are separate boundaries. Consumer idempotency protects business effects; DLQ records can still duplicate. |
| No service discovery | Six fixed localhost targets are enough for the reproducible environment. URLs are configurable; deployment topology/discovery is an external concern. |
| No Kubernetes / Terraform | Local Docker infrastructure is sufficient to review engineering behavior. Cluster provisioning/deployment would add scope without improving these demonstrations. |
| No Schema Registry | Explicit versioned JSON contracts and consumer-owned DTO validation suffice for the current event inventory. Coordinated schema evolution is manual; unsupported supported-type versions go to DLQ. |
| No Redis | No measured shared-cache/session bottleneck. PostgreSQL owns refresh state; access/service tokens use process memory. Multi-instance cache coordination is not claimed. |
| No Elasticsearch | Exact indexed audit filters and PostgreSQL JSONB cover current investigation needs. No full-text audit-search requirement exists. |
| No automatic JWT key rotation | External PEM configuration isolates signing but lacks JWKS/overlapping-key rollout. Rotation needs a deliberate operational design. Public fixture keys are local/test only. |
| No immediate access revocation | Stateless JWT validation avoids per-request Auth calls. Logout/disable can leave an access token usable until expiry; refresh is revoked/rejected. |
| No tracing backend | Correlation IDs, Micrometer and curated Operations metrics show local behavior. No persisted distributed spans, historical dashboards or alerting backend. |
| No real notification provider | Durable simulated rows demonstrate delivery/idempotency without sending messages to real people or managing provider credentials. |
| Offset pagination and exact totals | Simple stable API with creation/UUID ordering. Deep offsets and exact counts still have linear costs; indexes do not remove those limits. |
| One local PostgreSQL account / Kafka broker | Convenient isolated development, not production least-privilege grants, HA, TLS or broker authentication. |
| No retention / automatic DLQ replay | Keeps replay policy and destructive cleanup explicit. Outbox, processed markers, refresh rows, audit and DLQ storage need operational retention for real deployment. |
| Educational pricing | Deterministic BigDecimal rule calculation highlights software behavior, not risk modeling, underwriting, legal compliance or actuarial validity. |

Possible future improvements include JWKS/key rotation, mTLS, DLQ inspection/replay tooling, cursor pagination, real provider adapters, Prometheus/Grafana, a tracing backend, Kubernetes and a CI/CD deployment environment. None is part of the completion criteria for this portfolio.
