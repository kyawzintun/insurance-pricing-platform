# Phase 6 — Pricing Service MVP

Source: approved Phase 6 implementation request. Scope and implementation choices are recorded here for later phases.

## Goal and scope

Calculate educational THB premiums from the existing pricing_db rules and return a historical-snapshot-ready breakdown. Only Pricing Service implementation changes; existing services, infrastructure, schemas, and seeds are preserved.

## Functional behavior

Validate required request fields; derive completed driver age and vehicle age using a single UTC clock instant. Query enabled rules effective at that instant. Require a positive base premium. Evaluate numeric operators including inclusive BETWEEN, and coverage EQUALS. Choose the newest effective matching rule per category with UUID-text ascending tie-break. Apply factors in DRIVER_AGE, DRIVING_EXPERIENCE, VEHICLE_AGE, VEHICLE_VALUE, PREVIOUS_CLAIMS, COVERAGE_TYPE order. Round base and every step with BigDecimal HALF_UP to two decimals. Invalid active configuration fails safely.

Implementation assumptions: year lower bound 1886; input vehicle value up to 13 integer/two fractional digits; monetary output bound matches existing NUMERIC(15,2); numeric operands plain decimals up to 15 integer/four fractional digits; zero adjustment factors allowed by existing schema; engine size validated but unused. These are MVP limits, not actuarial or licensing rules.

## API impact

POST /internal/v1/pricing/calculate accepts birth date, driving experience, manufacturing year, vehicle value, engine size, claims count, and coverage. No customer/quote IDs or client-derived ages. Response contains base rule ID/version, base/final premium, THB currency, calculation instant, and sequenced adjustment rule IDs/versions, descriptions, inputs, factors, before/after amounts. DTOs only. Invalid input is 400 INVALID_PRICING_REQUEST; invalid/unavailable rules are 500 PRICING_CONFIGURATION_ERROR.

## Database and Kafka impact

Map pricing_rules with JPA; query only. No migrations or seed modifications. Calculation writes no rules, results, outbox events, or other rows. No producers, consumers, or topic changes.

## Security considerations

Only the internal calculation POST is temporarily allowed along with GET health/info. Pricing stays loopback by default and is not exposed through Gateway. This is not production service authentication; Phase 25 adds that. No OAuth2 Client Credentials in Phase 6.

## Testing and Definition of Done

Cover rule/operator boundaries, both coverage types, base-only and missing/invalid base behavior, deterministic ordering, overlapping rules, disabled/future rules, malformed configuration, validation, safe errors, and per-step rounding using a fixed clock. Normal Maven tests require no live database or H2. Validate actual JPA queries and seeded premiums through port 8083 with PostgreSQL, including read-only database evidence. All six services must pass root clean verify. Document limitations and leave Phase 7 unimplemented.

See [service guide](../../services/pricing-service/README.md) and [validation results](../phase-6-validation.md).
