# Insurance Pricing Platform — Angular Frontend

Phase 11 — Angular Quote Flow is complete. Registration/login, local logout, CUSTOMER quote creation, paginated history and stored quote details are implemented.

## Tooling and commands

Angular/CLI 22.2.2, NgRx SignalStore 22.0.1, TypeScript 6.0, npm, and Vitest with Angular's test builder. Components are standalone; TypeScript and template checking are strict. Use a supported Node version (`^22.22.3 || ^24.15.0 || >=26.0.0`); validation used Node 24.21.0 and npm 11.19.0. The lockfile pins the installed dependency tree.

From this directory:

```bash
npm ci
npm start
```

Open http://localhost:4200. Stop with Ctrl+C. In separate terminals, start infrastructure, Auth and Gateway using the [backend guide](../services/README.md). For registration/login, Quote and Pricing need not run. Do not start a second instance of an already running service.

```bash
# From the repository root, each long-running service in its own terminal:
docker compose up -d
./mvnw -pl services/auth-service spring-boot:run
./mvnw -pl services/api-gateway spring-boot:run
```

Validation commands, from `frontend/`:

```bash
npm test
npm run build
```

Tests use mocks and Angular HTTP testing; they do not need backend services. Build output is `dist/insurance-pricing-frontend/browser/`. No lint script is configured.

## Routes and behavior

| Route                  | Behavior                                                                                                  |
| ---------------------- | --------------------------------------------------------------------------------------------------------- |
| `/register`            | First name, last name, email, password; success redirects to login with an indication, without auto-login |
| `/login`               | Successful login stores the session and navigates to dashboard                                            |
| `/dashboard`           | Authenticated placeholder with CUSTOMER/ADMIN text                                                        |
| `/` and unknown routes | Resolve to dashboard, or login if unauthenticated                                                         |

Authenticated users visiting login/register return to dashboard. Registration validates required fields, email, lengths, a minimum 12-character password, and the backend's 72 UTF-8 byte maximum. Safe local messages handle duplicate registration, invalid credentials, validation failure, and unavailable Gateway. Raw backend errors are not displayed.

## Configuration and service boundary

`src/environments/environment.ts` contains the single public, build-time `apiBaseUrl`, defaulting to `http://localhost:8080`. Edit it and restart/rebuild when changing Gateway. Frontend configuration is public: never put signing keys or credentials there. Angular calls Gateway only; it does not call backend service ports directly. Existing Gateway CORS permits the development origin `http://localhost:4200`.

## State ownership

| Mechanism        | Owns                       | Examples                                                                          |
| ---------------- | -------------------------- | --------------------------------------------------------------------------------- |
| Reactive Forms   | Form values and validation | Email, password, required/length errors                                           |
| Angular Signals  | Local/component UI state   | Loading, safe error messages, password visibility; `computed` password input type |
| NgRx SignalStore | Shared/domain state        | Auth session, quotes, audit queries/results, operations overview                  |

Shared state uses four root-provided stores: `AuthStore`, `QuoteStore`, `AuditStore`, and
`OperationsStore`. The migration from classic Store/Effects removes dispatch/selector boilerplate
and lets components read domain signals directly. See the [NgRx SignalStore guide](https://ngrx.io/guide/signals/signal-store).

- **Reactive Forms = forms:** values, controls and validation stay in components.
- **Signals = local UI state:** password visibility, catalog options/loading, filter validation,
  metadata expansion and operations auto-refresh toggle stay local.
- **SignalStore = shared/domain state:** session, quote snapshots/history, applied audit query,
  operations overview and associated request status/errors.

`withState` exposes read-only state signals; `withComputed` derives ADMIN/CUSTOMER state in
`AuthStore`, replacing role selectors. Components use those signals directly, without converting
them to Observables. `withMethods` owns state transitions and navigation. Observable API side
effects use `rxMethod`: quote/audit reads use `switchMap` to cancel older requests, while quote
creation and operations refresh use `exhaustMap` to ignore overlaps. Each inner workflow catches
errors, settles loading and returns a safe local message, keeping the method alive for retries.

Login calls `AuthApiService` directly with transient form credentials and passes only the decoded
session to `AuthStore.loginSuccess`. Passwords never enter shared state and are cleared after
submission. Login-page request cancellation and errors remain local. Registration creates no shared
session. Auth navigation now runs in store methods instead of Effects.

`SessionService` remains the Observable coordination boundary for startup restore, interceptor
retries, single-flight refresh (`shareReplay`), bounded timeouts and cookie logout ordering. It
reads/writes `AuthStore`; it holds no duplicate session state. Refresh restoration updates the
session without login navigation or clearing current feature requests.

Logout, login failure, explicit auth reset and new login synchronously invoke
`SessionResetService`. This small callback registry holds no domain state. Instantiated protected
stores register their reset method: first cancel pending requests, then clear all protected data,
loading and errors. Store destruction unregisters callbacks and cancels requests through
`withHooks`. Cancellation leaves `rxMethod` usable for the next session. Operations `leave()` also
cancels a pending refresh while retaining its last snapshot, matching prior page-exit behavior.
A cancelled POST cannot undo a completed backend transaction.

No classic NgRx Store/Effects providers, actions, reducers, selectors, effects or dependencies
remain. Router/HTTP providers and the startup initializer are unchanged.

## Authentication boundaries

The access token lives only in NgRx memory. No localStorage/sessionStorage persistence exists. Browser reload attempts one bounded refresh-cookie session restoration. Access tokens remain memory-only; refresh/logout and a single-refresh queue are implemented.

Client decoding reads `sub`, `roles`, and `exp` and rejects malformed/expired payloads for UI purposes. It does not verify signatures; Gateway and services remain authoritative. The dashboard guard checks shared state and expiry for navigation convenience. Expiry is checked on navigation/protected API requests, not by a background timer.

The interceptor attaches Bearer only to the configured Gateway's `/api/` requests, excluding POST login/register/refresh/logout. Other origins and similarly prefixed paths do not receive it. A protected request's 401 joins one shared refresh and retries after success; refresh failure clears local state. A late 401 for an older token does not discard a newer session. Public login errors remain on the login page.

Logout clears token, user ID, roles, and authentication state and navigates to login. It also revokes the server refresh token; an already issued JWT remains valid until expiry.

## Import aliases

TypeScript imports use project aliases configured in `tsconfig.json`:

- `@app/*` maps to `src/app/*`.
- `@environments/*` maps to `src/environments/*`.

For example, `import { AuthStore } from '@app/store/auth/auth.store';` works in application code, tests, and lazy route imports. These are project-relative aliases, not machine-specific filesystem paths. Component template/style URLs remain relative to their component files.

## Code reading order

1. `app.routes.ts` and `app.config.ts`: routes, providers, session initializer and interceptor registration.
2. `core/auth/auth.models.ts`, `gateway.config.ts`, `auth-api.service.ts`: backend contracts and Gateway calls.
3. `features/auth/login/` and `register/`: Reactive Forms, local Signals, submission and safe errors.
4. `store/auth/`: SignalStore session state, computed roles, expiration helper and navigation methods.
5. `core/auth/decode-session.ts`: defensive UI claim decoding.
6. `core/interceptors/auth.interceptor.ts` and `core/guards/auth.guard.ts`: shared session use.
7. `app.component.*` and `features/dashboard/`: navigation and role-aware placeholder.
8. Adjacent `*.spec.ts` files: executable examples of expected behavior.

Pricing administration, persistent login, SSR, PWA, and messaging UI are deferred. See the [Phase 10 specification](../docs/specs/phase-10-angular-foundation.md) and [validation report](../docs/phase-10-validation.md).

## Phase 11 — Customer quote workflow

Start Auth, Gateway, Quote and Pricing in separate terminals using the backend guide, plus the shared infrastructure. Then `npm start` from this directory. Restart Gateway and Quote after pulling Phase 11 changes so the new catalog routes are available. Existing default ports remain unchanged.

```bash
# Each command in its own terminal, from the repository root:
./mvnw -pl services/quote-service spring-boot:run
./mvnw -pl services/pricing-service spring-boot:run
```

| Route         | CUSTOMER behavior                                                       |
| ------------- | ----------------------------------------------------------------------- |
| `/quotes/new` | Driver, vehicle and coverage form; success opens quote detail           |
| `/quotes`     | Owned quotes, newest first, Previous/Next pagination                    |
| `/quotes/:id` | Stored driver, vehicle and pricing snapshot; safe missing-quote message |

The CUSTOMER guard requires an active session and CUSTOMER role. ADMIN-only accounts see no New Quote/My Quotes links and return to dashboard if they enter those routes; no admin quote-management UI is provided. Backend authorization/ownership remains authoritative.

Vehicle selections load active brands and models from Gateway GET `/api/v1/vehicles/brands` and `/api/v1/vehicles/brands/{brandId}/models`. Quote Service owns these reference tables. Angular contains no seed catalog. Changing brand clears the model and cancels older model requests.

Reactive Forms own all nine inputs: dateOfBirth, drivingExperienceYears, previousClaimsCount, vehicleBrandId, vehicleModelId, vehicleManufacturingYear, vehicleValue, engineSizeCc and coverageType. Required fields, past birth date, nonnegative integer experience/claims, year 1886 through current UTC year, positive two-decimal vehicle value, positive integer engine size and coverage are validated locally. Backend remains authoritative.

`QuoteApiService` uses the same public Gateway URL and auth interceptor as authentication. No component attaches JWT manually, sends customerId/status/premium, calls Pricing, or calculates premiums. Create calls `QuoteStore.createQuote`; its `rxMethod` posts the inputs and navigates to `/quotes/{id}` after success. Detail then retrieves the persisted snapshot. Never automatically retry a failed creation request: without idempotency, an ambiguous network failure may have already persisted a quote; check My Quotes before retrying.

### Quote state ownership

- **Reactive Forms:** input values and validation only.
- **Local Signals:** brands/models, catalog loading/error, and expanded pricing breakdown.
- **SignalStore:** last created quote, selected detail, history and pagination. Store methods own create/list/detail requests. Request status and safe errors are read directly as signals. Form controls stay in Reactive Forms.

`exhaustMap` prevents concurrent create submissions; list/detail `switchMap` discards superseded requests. Logout, login failure, and new login reset quote state and cancel pending quote requests, preventing an old session from repopulating another account's data. A cancelled POST cannot undo a server-side transaction that already completed.

History defaults to page 0, size 20; only page and size are sent. Previous/Next use backend totals and disable at boundaries. Detail renders historical names and pricing adjustments directly from the response. It does not reload current vehicle names or pricing rules. Money uses currency formatting to two decimals; factors use four decimals; timestamps display locally without altering UTC data. Birth dates remain calendar dates with no timezone shift.

400/404/502/503 responses display safe messages. Protected 401 handling stays in the existing auth interceptor. No refresh tokens, edits, deletes, repricing, automatic expiration, admin UI, or Kafka events are implemented. Optional live pricing-rule mutation was unnecessary because PostgreSQL snapshot regression and frontend snapshot rendering tests cover that boundary. See [Phase 11 specification](../docs/specs/phase-11-angular-quote-flow.md) and [validation](../docs/phase-11-validation.md).

## Phase 22 — ADMIN Audit UI

ADMIN navigation now includes Audit: `/admin/audit` and `/admin/audit/:id`. CUSTOMER sees no link and is redirected to dashboard by the guard. Angular calls only Gateway. Reactive Forms own exact filters and UTC ranges; Signals own validation/metadata visibility; AuditStore owns applied query, paginated rows, detail and request state. Query parameters preserve applied filters/page/size through detail/back navigation. All audit state resets and requests cancel on session change.

Apply/Clear, Previous/Next, loading/empty/error/retry and not-found states are supported. Occurrence time is shown in UTC, newest first. Detail metadata uses text interpolation and JSON formatting, never HTML. Nothing edits or deletes audit records. Phase 23 restores sessions on reload. See [full contract](../docs/specs/phase-22-audit-ui.md) and [validation](../docs/phase-22-validation.md).

## Phase 23 — Refresh tokens

The current session flow uses short-lived access JWTs in Angular memory and rotating
opaque refresh tokens in HttpOnly cookies, with SHA-256 digests in the existing Auth
table. POST refresh/logout are public through Gateway. Secure defaults to true
(local HTTP profile false); SameSite=Lax, auth-only path, TTL 7 days, all configurable.
Gateway permits credentials only for configured exact origins. Angular restores the
session before initial navigation and shares one refresh across concurrent 401s.
Logout revokes the cookie; disabled-user refresh is rejected and revoked. PostgreSQL
row locks serialize rotation, with rollback on replacement failure. Access JWTs
remain valid until expiry. No token events or Phase 24 changes are included.

See [Phase 23 specification](../docs/specs/phase-23-refresh-tokens.md) for configuration,
CSRF assumptions, queue behavior, transaction guarantees and deferred cleanup/family tracking.
Earlier phase descriptions below/above are historical where superseded by Phase 23.

## Phase 27 — Centralized Operations Dashboard

ADMIN can open `/admin/operations`. Angular calls only Gateway's
`GET /api/v1/admin/operations`; a focused Gateway component concurrently collects the six
services' existing Phase 26 health/readiness and curated metrics. Gateway reads its own health
locally. Per-service failure preserves HTTP 200 and the other cards; overall becomes DEGRADED.
The fixed DTO excludes internal URLs, raw Actuator data and credentials. ADMIN authorization
is enforced by Gateway; its existing JWT is relayed only to trusted protected metric endpoints.
Collection has a configurable 1000 ms deadline per exchange and bounded response bodies.
Manual refresh and pausable 15-second refresh use OperationsStore and local Signals.
Pricing circuit/latency/errors reuse Quote-owned metrics. Counters reset at restart; no
history, Prometheus/Grafana stack, database performance work or next phase is included.
See [specification and configuration](../docs/specs/phase-27-centralized-operations.md) and
[validation](../docs/phase-27-validation.md). This supersedes the old Phase 27 performance placeholder.
