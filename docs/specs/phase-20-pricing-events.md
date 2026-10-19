# Phase 20 — Pricing Events

Scope: PricingRuleCreated, PricingRuleUpdated, PricingRuleEnabled and
PricingRuleDisabled v1 only. Each successful existing ADMIN mutation stores a
compact resulting-state event in the same transaction as the optimistic-lock rule
write. Authenticated UUID actor, preserved request correlation, one injected UTC
operation instant, pricingRuleId key, pricing-service producer and PricingRule aggregate.

Reuse the existing pricing outbox and Quote/Auth session advisory-lock polling
pattern; acknowledgment gates PUBLISHED. Immutable event identity supports
at-least-once resend. One append-only Pricing V4 correlation-width migration.

Audit consumes through a focused decoder/listener, maps ADMIN/PricingRule and
allowlisted metadata, and reuses processed_events plus Phase 17 retry/pricing DLQ.
No consumer migration, Notification listener or new topic. Preserve unknown-type skip,
quote/user consumers, exact DLQ bytes, safe diagnostics and acknowledgment semantics.

Required validation: normal Maven, real PostgreSQL atomicity/rollback/version races,
Kafka lifecycle/key/order/outage recovery, Audit four-type/duplicate/pricing DLQ cases,
and disposable Gateway→Pricing→Audit with ADMIN identity, duplicate replay, offsets,
security and calculation regression evidence. See [results](../phase-20-validation.md).

See [complete contract, flow, configuration and limitations](../pricing-rule-events.md).
No historical backfill, PricingCalculated, deletion/bulk events, customer notifications,
frontend/customer-management changes, new authentication features or Phase 21.
