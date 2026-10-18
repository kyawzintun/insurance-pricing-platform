# Implementation Specifications

This directory will hold feature specifications for specification-first, incremental implementation. No implementation specifications are created in Phase 0.

Each implementation feature may later have a specification covering:

- Goal
- Scope
- Functional behavior
- API impact
- Database impact
- Kafka impact
- Security considerations
- Testing requirements
- Definition of Done

Record approved behavior and identify unresolved questions before implementation. Keep specifications aligned with the current phase and approved architecture.

## Phase 18 — Quote Expiration

Automatic PRICED → EXPIRED transitions use bounded PostgreSQL transactions and the existing outbox. Notification supports QUOTE_EXPIRED; Audit uses a SYSTEM actor. See the [Phase 18 specification](phase-18-quote-expiration.md) for configuration, contract, concurrency and failure semantics. Phase 19 is outside this change.

- [Phase 19 — Auth/User Events](phase-19-auth-user-events.md)
