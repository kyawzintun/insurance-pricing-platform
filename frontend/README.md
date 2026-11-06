# Insurance Pricing Platform — Angular Frontend

Completed Angular application with NgRx SignalStore. CUSTOMER pages provide registration/login, session restoration, quote creation/history/detail. ADMIN pages provide Audit and Operations. Pricing/customer administration remains API-only.

## Tooling and Commands

Angular/CLI 22.2.2, NgRx Signals 22.0.1, TypeScript 6.0, RxJS and Reactive Forms. Components are standalone with strict TypeScript/template checks. Use Node `^22.22.3 || ^24.15.0 || >=26.0.0`; npm is pinned to 11.19.0. Vitest/jsdom runs through Angular's test builder.

From `frontend/`:

```bash
npm ci
npm start
npm test
npm run build
```

`npm start` serves localhost:4200; `npm test` is non-watch and needs no backend. Production output is `dist/insurance-pricing-frontend/browser/`. No lint script is configured. For real workflows, start infrastructure and all six services using the [local-development guide](../docs/local-development.md). Registration/login needs Auth/Gateway; quotes also require Quote/Pricing and service-token issuance. Consumers process side effects asynchronously.

## Routes

| Route | Access / behavior |
| --- | --- |
| `/register`, `/login` | Guest-only forms; registration redirects to login |
| `/dashboard` | Authenticated role-aware landing page |
| `/quotes/new` | CUSTOMER quote creation with seeded reference choices |
| `/quotes` | CUSTOMER paginated history |
| `/quotes/:id` | CUSTOMER historical snapshot and premium breakdown |
| `/admin/audit`, `/admin/audit/:id` | ADMIN filtered history and immutable detail |
| `/admin/operations` | ADMIN six-service health/metrics with partial failures |
| `/`, unknown routes | Redirect to dashboard, then login when unauthenticated |

Guards and navigation improve presentation; backend authorization remains authoritative. ADMIN API quote retrieval exists, but Angular quote pages are CUSTOMER-only.

## Configuration and Security Boundary

`src/environments/environment.ts` contains public build-time `apiBaseUrl`, default `http://localhost:8080`. Change it and restart/rebuild for another Gateway. Angular calls Gateway only. The interceptor attaches bearer tokens only to its API origin/path, excludes auth session operations and never uses a customer token for internal Pricing.

Access JWTs stay in memory. An HttpOnly rotating cookie supports restoration before initial navigation and one shared refresh across simultaneous protected 401s. A 403 does not refresh. Logout clears protected state and revokes the refresh cookie. Localhost is the documented cookie/CORS topology; use the same hostname consistently. See [security limitations](../docs/security.md), including access-token validity after logout/disable.

## State Ownership

| Mechanism | Responsibility | Examples |
| --- | --- | --- |
| Reactive Forms | Inputs and validation | Auth fields, quote fields, editable audit filters |
| Local Signals | Presentation state | Catalog loading, safe local errors, expansion, auto-refresh toggle |
| NgRx SignalStore | Shared domain/session state | AuthStore, QuoteStore, AuditStore, OperationsStore |

SignalStore methods/computed signals replace classic actions/reducers/effects/selectors. `rxMethod` reads use switchMap cancellation; quote creation and Operations refresh use exhaustMap to prevent overlap. Errors stay inside the request pipeline so later retries work. SessionService bridges observable refresh coordination to AuthStore; SessionResetService cancels and clears instantiated protected stores on session changes.

Audit query parameters preserve applied filters/page across detail/back navigation. Metadata is text/JSON, never HTML. Operations supports manual and pausable 15-second refresh, cancels work on exit, retains a labeled prior snapshot on error, and handles missing metrics without inventing values. Runtime counters reset on process restart.

Quote detail renders stored names and premium adjustments without recalculation or reloading current rules. History sends only page/size and follows server totals; no client-supplied customer identity overrides ownership. Money/factors and UTC/calendar-date boundaries follow backend DTOs.

## Validation and History

Tests cover stores, components, guards, interceptor origin restrictions, refresh queues, cancellation and cross-session clearing. See [testing strategy](../docs/testing.md) and [final results](../docs/portfolio-validation.md). The [SignalStore migration report](SIGNALSTORE_MIGRATION.md) preserves the earlier migration's file inventory and verification, while phase10/11/22/27 reports retain historical browser evidence.
