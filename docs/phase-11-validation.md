# Phase 11 — Angular Quote Flow Validation

Completed 2026-10-11. Scope: CUSTOMER Angular quote flow and minimal read-only vehicle reference APIs. Phase 12 is not started.

## Automated validation

| Command | Result |
| --- | --- |
| `npm ci --cache /tmp/insurance-npm-cache` in frontend | Passed; 281 packages installed, audit reported no vulnerabilities |
| `npm test` in frontend | 85 tests across 9 files passed |
| `npm run build` in frontend | Production build passed, including lazy quote pages |
| `./mvnw -B clean verify` | All six modules passed; 273 tests, zero failures/errors/skips |
| `./mvnw -B -pl services/quote-service -Ppostgres-it verify` | 8 disposable PostgreSQL integration tests passed |
| `git diff --check` | Passed |

No lint script exists. Final frontend tests/build ran after formatting and the birth-date display correction. The original 50 auth tests remain part of the suite.

Frontend tests cover Gateway catalog/create/list/detail contracts, interceptor Bearer attachment, input validation, brand/model cancellation and selection reset, safe unavailable/error responses, duplicate-submit prevention, quote reducer/selectors, effect requests/navigation/cancellation, logout/session isolation, history pagination, historical driver/vehicle/pricing rendering, exact birth-date rendering, safe missing details, CUSTOMER guards/navigation and ADMIN-only exclusion.

Backend additions test authenticated catalog access, missing/inactive brands, invalid UUIDs, DTO shape, rejected writes, and Gateway token/path forwarding. PostgreSQL tests verify active brand/model filtering, model parent scoping and unchanged database contents after reads. Existing ownership, pagination, transaction rollback and snapshot tests still pass.

## Live browser flow

Existing ports were occupied at the initial inspection. Used a temporary copy of the frontend on 4300 with only its public Gateway URL changed to 18080; temporary Quote ran on 18082. Repository defaults remain frontend 4200, Gateway 8080 and Quote 8082. Auth and Pricing stopped listening during the run; temporary instances were started on their then-free standard ports 8081/8083. Gateway used explicit IPv4 loopback downstream URLs for this validation. No user-run services were stopped by this task.

1. Registered a fictional temporary CUSTOMER through Angular/Gateway; received the login success indication.
2. Logged in and verified New Quote/My Quotes navigation.
3. Loaded brands from the backend and selected Toyota; models loaded dynamically, including Camry.
4. Submitted driver/vehicle inputs with comprehensive coverage. Navigated to persisted detail showing PRICED, reference, expiry, snapshots, THB 8,000.00 base premium, factor 1.4000 and THB 11,200.00 final premium.
5. Opened My Quotes, found the new quote, reopened it, and confirmed the same stored result.
6. Created a second Honda Civic quote with third-party coverage; displayed THB 8,000.00 and factor 1.0000. Both quotes appeared newest-first in history.
7. Compared both displayed premiums directly with their stored database amounts: exact matches.
8. Verified one-page pagination boundaries (Previous/Next disabled) and two total entries. Multi-page Previous/Next behavior is covered by component tests and existing PostgreSQL pagination tests, not by creating 21 live quotes.
9. Inspected desktop detail and mobile history at width 390; document and content width both 390, with no horizontal overflow.
10. Logged out and opened `/quotes/new`; redirected to login.

Live validation exposed a date-only formatting issue: Angular interpreted a birth date in local time before UTC formatting. Rendering an explicit UTC date-only instant corrected it. The browser then displayed Apr 20, 1995 exactly as submitted; an exact-date assertion protects this behavior.

All application HTTP in the validation build targeted the configured Gateway; the existing interceptor supplied JWT automatically. Angular never called Quote or Pricing directly. The temporary setup also exercised the safe connection-failure message while Auth was unavailable.

## Historical snapshot boundary

No live pricing-rule mutation was necessary. The existing PostgreSQL test changes current catalog/pricing behavior and proves retrieval remains the original stored response. Frontend detail tests assert stored names, driver data, factors and premiums and verify no catalog request. Live history/detail reopening displayed the same persisted premium. No frontend pricing calculations exist.

## Data and process cleanup

Captured fingerprints before validation for Auth users/refresh_tokens/outbox/Flyway, all Quote snapshot/reference/outbox/Flyway tables, and Pricing rules/outbox/Flyway, plus Kafka topic offsets. During validation, only the temporary Auth user and Quote snapshot tables changed. Outbox, references, Pricing rules, migrations and Kafka offsets remained unchanged.

Deleted only the temporary customer's two quote aggregates and account using local maintenance, without adding deletion APIs. **All captured database fingerprints and Kafka offsets matched the original baseline after cleanup.** Stopped all five task-started processes (Angular, Gateway, Quote, Auth and Pricing); their validation ports were confirmed free. Shared Docker infrastructure was left intact.

## Scope and limitations

No Auth/Pricing production changes, Flyway migrations, Kafka producers/consumers, outbox publishing, quote edits/deletes/repricing, automatic expiration or admin UI. Memory-only authentication still logs out on reload. Duplicate-click prevention is not server idempotency; a lost create response can leave a stored quote, so check history before retrying. Cancelling a pending client request cannot roll back an already completed server transaction.

Phase 11 is complete and ready for Phase 12 — Basic QuotePriced Kafka Event. No commit was created.
