# Phase 10 — Angular Foundation Validation

Completed on 2026-10-11. Scope: frontend authentication foundation only.

## Automated checks

| Check | Result |
| --- | --- |
| `npm ci` | Passed; lockfile installation succeeded using a temporary npm cache |
| `npm test` | 50 tests passed across 6 files; no backend required |
| `npm run build` | Production build passed; initial bundle 278.45 kB, estimated transfer 77.81 kB |
| `./mvnw -B clean verify` | All six service modules successful; 267 tests, zero failures/errors/skips |
| `git diff --check` | Passed |

Tooling: Angular/CLI 22.2.2, NgRx Store/Effects 22.0.1, TypeScript 6.0, Vitest 5.0.3; Node 24.21.0/npm 11.19.0. No lint script exists. The final frontend test/build runs occurred after removal of all temporary browser-validation code.

Tests cover Gateway request contracts, form validation/submission/password clearing, local UI Signals, safe errors, register redirect, reducer/selectors, navigation effects, malformed/expired JWT decoding, interceptor attachment boundaries and 401 handling (including stale responses), guards, logout, and CUSTOMER/ADMIN rendering.

## Live browser checks

Used Angular on localhost:4200 and existing Gateway/Auth on ports 8080/8081. Existing Gateway CORS worked without backend changes.

- Registered a temporary fictional CUSTOMER through the UI; redirected to login with success indication.
- Wrong password displayed a safe generic error; duplicate registration displayed a safe duplicate-email error.
- Correct login reached dashboard with authenticated navigation and CUSTOMER content.
- Confirmed shared authentication state and a Bearer-authenticated read through Gateway to the existing Quote API returned 200. A temporary UI probe observed the outgoing header after the real auth interceptor without displaying the token; the probe, route, observer and link were removed before final build. No quote UI remains.
- Logout removed authenticated navigation; direct dashboard access redirected to login.
- Reload after login cleared the memory-only session and returned to login.
- Desktop page rendering was visually inspected. Role variations and transient form state are additionally covered by automated tests.

The temporary account was deleted through existing local database maintenance, without adding an API. Auth users, refresh_tokens, outbox_events and Flyway history fingerprints matched their pre-validation values afterward. Kafka topic offsets also matched their baseline. No migrations or messaging were introduced.

The Angular dev server started for validation was stopped. Existing user-run backend services were left running. No commit was created.

## Scope and limitations

Backend production code, Maven configuration, Compose infrastructure, and migrations are unchanged. Frontend source contains no signing secret, direct service URLs, token storage persistence, or temporary validation routes. Public configuration contains only the Gateway base URL.

Token decoding and guards are UI conveniences, not cryptographic authorization. Logout clears frontend state without revoking tokens; browser reload intentionally logs out. Expiry is checked on guarded navigation/protected requests, without a background scheduler. Refresh-token support, quote creation/history UI, pricing administration UI, and Phase 11 remain unimplemented.

Phase 10 is complete and ready for Phase 11 — Angular Quote Flow.
