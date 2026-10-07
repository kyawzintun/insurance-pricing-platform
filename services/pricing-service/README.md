# Pricing Service — Phase 6 MVP

Calculates an educational THB car-insurance premium from active rules in `pricing_db`. These values are **not actuarial pricing**. Calculation is read-only: no result, quote, outbox event, or Kafka message is persisted/published.

## Run locally

From the repository root with Java 25:

```bash
docker compose up -d
./mvnw -pl services/pricing-service spring-boot:run
```

Pricing defaults to the `local` profile, loopback port **8083**, and PostgreSQL at `localhost:15432/pricing_db`, matching the existing learning setup. No launcher script is needed. Environment variables `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `PRICING_SERVICE_PORT`, and `SERVER_ADDRESS` override local defaults. Maven does not load `.env` automatically. A non-local profile requires explicit datasource configuration. Stop the application with Ctrl+C.

## Internal API

**POST `/internal/v1/pricing/calculate`** is temporarily allowed without authentication. It is not routed through Gateway. Keep Pricing private; this is not production-secure. Real service-to-service authentication is deferred to Phase 25. Health/info remain public; all other requests remain denied.

```bash
curl -i http://localhost:8083/internal/v1/pricing/calculate \
  -H 'Content-Type: application/json' \
  -d '{
    "dateOfBirth": "1995-04-20",
    "drivingExperienceYears": 8,
    "vehicleManufacturingYear": 2021,
    "vehicleValue": 700000,
    "engineSizeCc": 1500,
    "previousClaimsCount": 1,
    "coverageType": "COMPREHENSIVE"
  }'
```

With the unchanged seeds, evaluated on 2026-10-07, the response is:

```json
{
  "basePricingRuleId": "75ddf7df-77ce-57bc-8604-f2c0b95b76c5",
  "basePricingRuleVersion": 0,
  "basePremium": 8000.00,
  "finalPremium": 11200.00,
  "currency": "THB",
  "calculatedAt": "2026-10-07T10:00:00Z",
  "adjustments": [
    {
      "pricingRuleId": "e8b09cf1-c3f3-57e3-a1fb-1dc907680679",
      "pricingRuleVersion": 0,
      "ruleType": "COVERAGE_TYPE",
      "description": "Learning-only COVERAGE_TYPE rule",
      "inputValue": "COMPREHENSIVE",
      "factor": 1.4000,
      "amountBefore": 8000.00,
      "amountAfter": 11200.00,
      "sequenceNumber": 1
    }
  ]
}
```

`calculatedAt` is the actual UTC calculation instant. Premiums and age-dependent adjustments can change with time or configured rules. Base and adjustment rule IDs/versions support later historical snapshots; this service does not store the response.

## Validation and derived values

All seven request fields are required. Birth date must be strictly before today's UTC date. Experience and claims must be nonnegative integers. Manufacturing year must be from **1886 through the current UTC year**. Vehicle value must be positive, with up to 13 integer digits and two decimal places; engine size must be a positive integer. Coverage must be the string `COMPREHENSIVE` or `THIRD_PARTY`; numeric enum ordinals and fractional values for integer fields are rejected.

Unknown fields, including customerId, quoteId, driverAge, and vehicleAge, are rejected. Driver age is completed years from birth date; vehicle age is current year minus manufacturing year. Both use the same captured UTC instant as rule selection and the response timestamp. No driving-age/licensing constraints are invented. `engineSizeCc` is validated but does not affect the premium: no engine-size rule type exists in this phase.

## Rule selection and calculation

`PricingRuleRepository` exposes just one query: enabled rules with `effective_from <= now`. No repository write operation is exposed. The service uses `@Transactional(readOnly = true)` and validates all active rules before evaluation. Inactive rules are ignored, even if their configuration is invalid.

1. Select a valid active `BASE_PREMIUM`: `fixed_amount > 0`, no factor or operands. Its stored `EQUALS` operator is the Phase 3 placeholder, not a comparison.
2. Evaluate adjustment predicates. Missing matching categories leave the premium unchanged; absence of an active base fails.
3. For each category, select **one matching rule**: newest `effective_from` first; ties use UUID string ascending. The same strategy selects the base. A newer nonmatching rule does not suppress an older matching rule. Rules are not stacked within a category.
4. Apply categories in this order: `DRIVER_AGE`, `DRIVING_EXPERIENCE`, `VEHICLE_AGE`, `VEHICLE_VALUE`, `PREVIOUS_CLAIMS`, `COVERAGE_TYPE`.
5. Multiply the current amount by the selected factor and append a sequenced breakdown. Base is not an adjustment. A factor of 1 is still included; zero factors are allowed consistently with the existing schema.

Numeric categories support `EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, and inclusive `BETWEEN`. BETWEEN requires ordered lower/upper values. Coverage supports only `EQUALS` against a supported enum name; numeric ordering of coverage is invalid.

Comparison operands are plain signed decimals (up to 15 integer digits and four fractional digits), not expressions/scientific notation. Invalid operands, missing/negative factors, conflicting fixed amounts, unsupported coverage comparisons, and invalid ranges fail safely. An unused second operand is rejected. All active rules must be valid, even if they would not match the current request. Unknown stored enum values also fail configuration loading.

All money and factors use `BigDecimal`. Base and **each multiplication step** round to two decimal places using `RoundingMode.HALF_UP`. Values are never calculated with float/double. Final amounts are bounded to `9999999999999.99` THB (the existing monetary precision); overflow returns a configuration error. Example: 0.05 × 1.10 → 0.06, then × 1.10 → 0.07. This differs intentionally from rounding only once at the end.

## Errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_PRICING_REQUEST` | Missing, malformed, unknown, or invalid request data |
| 500 | `PRICING_CONFIGURATION_ERROR` | Missing base, invalid active rules, monetary overflow, or unavailable rule data |
| 500 | `INTERNAL_ERROR` | Unexpected processing failure |

Errors contain only `code` and a safe `message`; no SQL, raw rule values, stack traces, or internal exception messages.

## Code reading order

- `dto/PricingRequest` — accepted inputs and field constraints.
- `controller/PricingController` — HTTP endpoint calling the service.
- `service/PricingService` — one calculation flow, explicit validation and operators.
- `repository/PricingRuleRepository` and `entity/PricingRule` — read-only query and existing table mapping.
- `dto/PricingResponse` / `PricingAdjustment` — immutable snapshot response.
- `config/PricingConfiguration` / `PricingSecurityConfiguration` — clock and temporary internal access.
- `exception/ApiExceptionHandler` — safe error mapping.

## Tests and limitations

```bash
./mvnw -pl services/pricing-service -am test
./mvnw clean verify
```

Unit tests inject a fixed clock and repository mock. HTTP tests run the real controller, validation, security, and service with a mocked repository. Neither needs Docker, PostgreSQL, or Kafka; no H2 is introduced. Actual mapping/query behavior is checked separately with live PostgreSQL; see [Phase 6 validation](../../docs/phase-6-validation.md).

Phase 6 adds no migrations, seed edits, admin CRUD, Gateway route, Quote integration, cache, Kafka handlers, outbox publishing, or rule-engine framework. Phase 7 — Pricing Administration has not started.
