# Domain Model

| Owner | Main concepts / invariants |
| --- | --- |
| Auth | User UUID, CUSTOMER/ADMIN, ACTIVE/DISABLED; BCrypt password; hash-only rotating refresh tokens |
| Quote | Quote UUID/reference, CUSTOMER owner, PRICED/EXPIRED lifecycle; driver, vehicle and pricing snapshots; quote expiration |
| Pricing | Effective/enabled pricing rules, fixed premiums/factors, optimistic version; deterministic BigDecimal calculation |
| Notification | Simulated CREATED notification derived from supported quote/registration events |
| Audit | Immutable event record with actor, entity, occurrence/correlation and allowlisted JSON metadata |

No entities or database foreign keys cross service boundaries. Quotes preserve names, selected rule identities/versions and premium results at creation rather than re-reading current pricing on retrieval. Producers own outbox intent; consumers own processed-event markers. See [database design](database-design.md) for actual tables and [event contracts](kafka-design.md) for communication.
