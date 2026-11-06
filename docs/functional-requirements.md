# Functional Requirements — Implemented Scope

- CUSTOMER registration/login/logout, session restoration and rotating refresh tokens.
- Quote creation with immediate database-rule pricing, owned history/detail and immutable historical snapshots.
- Automatic 30-day PRICED → EXPIRED transition with durable event intent.
- ADMIN APIs for pricing-rule list/detail/create/update/enable/disable with version conflicts.
- ADMIN APIs for CUSTOMER list/detail/enable/disable with serialized real transitions.
- ADMIN Audit UI/API with exact filters, pagination and immutable detail.
- ADMIN Operations UI/API with concurrent health/curated-metric collection and partial-failure handling.
- Asynchronous simulated notifications and audit records with outbox publication, idempotency and retry/DLQ.

Pricing/customer administration is API-only. No quote edit/delete/reprice, real notification delivery, admin registration or actuarial model is included. Current contracts are linked from the [README API/event overview](../README.md#api-overview); historical acceptance criteria remain in [specifications](specs/README.md).
