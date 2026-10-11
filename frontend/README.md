# Insurance Pricing Platform — Angular Frontend

Phase 10 — Angular Foundation is complete. Registration, login, a protected placeholder dashboard, and local logout are implemented. Quote UI belongs to Phase 11.

## Tooling and commands

Angular/CLI 22.2.2, NgRx Store/Effects 22.0.1, TypeScript 6.0, npm, and Vitest with Angular's test builder. Components are standalone; TypeScript and template checking are strict. Use a supported Node version (`^22.22.3 || ^24.15.0 || >=26.0.0`); validation used Node 24.21.0 and npm 11.19.0. The lockfile pins the installed dependency tree.

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

| Route | Behavior |
| --- | --- |
| `/register` | First name, last name, email, password; success redirects to login with an indication, without auto-login |
| `/login` | Successful login stores the session and navigates to dashboard |
| `/dashboard` | Authenticated placeholder with CUSTOMER/ADMIN text |
| `/` and unknown routes | Resolve to dashboard, or login if unauthenticated |

Authenticated users visiting login/register return to dashboard. Registration validates required fields, email, lengths, a minimum 12-character password, and the backend's 72 UTF-8 byte maximum. Safe local messages handle duplicate registration, invalid credentials, validation failure, and unavailable Gateway. Raw backend errors are not displayed.

## Configuration and service boundary

`src/environments/environment.ts` contains the single public, build-time `apiBaseUrl`, defaulting to `http://localhost:8080`. Edit it and restart/rebuild when changing Gateway. Frontend configuration is public: never put signing keys or credentials there. Angular calls Gateway only; it does not call backend service ports directly. Existing Gateway CORS permits the development origin `http://localhost:4200`.

## State ownership

| Mechanism | Owns | Examples |
| --- | --- | --- |
| Reactive Forms | Form values and validation | Email, password, required/length errors |
| Angular Signals | Local/component UI state | Loading, safe error messages, password visibility; `computed` password input type |
| NgRx Store | Shared/application authentication | Access token, user ID, roles, expiry, authentication flag |

This keeps short-lived page state near its component while giving guards, shell, dashboard, and interceptor one shared session. Components read selectors with `selectSignal`. There is no additional global state library or Signal Store.

Login calls the focused `AuthApiService`, then dispatches a password-free `loginSuccess` or `loginFailure`. Credentials stay transient in the form/HTTP request, never in NgRx actions or state, and the password control is cleared after submission. NgRx Effects handle shared navigation after login success and logout. Registration also calls the service directly because it creates no shared session. This deliberately avoids passing passwords through a login action.

## Authentication boundaries

The access token lives only in NgRx memory. No localStorage/sessionStorage persistence exists. **Refreshing the browser logs the user out intentionally.** No refresh tokens, refresh retries/queues, or backend logout endpoint are implemented.

Client decoding reads `sub`, `roles`, and `exp` and rejects malformed/expired payloads for UI purposes. It does not verify signatures; Gateway and services remain authoritative. The dashboard guard checks shared state and expiry for navigation convenience. Expiry is checked on navigation/protected API requests, not by a background timer.

The interceptor attaches Bearer only to the configured Gateway's `/api/` requests, excluding POST login/register. Other origins and similarly prefixed paths do not receive it. A protected request's 401 clears its matching current session and navigates to login without retry. A late 401 for an older token does not discard a newer session. Public login errors remain on the login page.

Logout clears token, user ID, roles, and authentication state and navigates to login. It is frontend-state logout only; it does not revoke an already issued JWT.

## Import aliases

TypeScript imports use project aliases configured in `tsconfig.json`:

- `@app/*` maps to `src/app/*`.
- `@environments/*` maps to `src/environments/*`.

For example, `import { AuthActions } from '@app/store/auth/auth.actions';` works in application code, tests, and lazy route imports. These are project-relative aliases, not machine-specific filesystem paths. Component template/style URLs remain relative to their component files.

## Code reading order

1. `app.routes.ts` and `app.config.ts`: routes, providers, Store and interceptor registration.
2. `core/auth/auth.models.ts`, `gateway.config.ts`, `auth-api.service.ts`: backend contracts and Gateway calls.
3. `features/auth/login/` and `register/`: Reactive Forms, local Signals, submission and safe errors.
4. `store/auth/`: actions, reducer/selectors, expiration helper and navigation effects.
5. `core/auth/decode-session.ts`: defensive UI claim decoding.
6. `core/interceptors/auth.interceptor.ts` and `core/guards/auth.guard.ts`: shared session use.
7. `app.component.*` and `features/dashboard/`: navigation and role-aware placeholder.
8. Adjacent `*.spec.ts` files: executable examples of expected behavior.

Quote creation/history, pricing administration, audit UI, persistent login, SSR, PWA, and messaging UI are deferred. See the [Phase 10 specification](../docs/specs/phase-10-angular-foundation.md) and [validation report](../docs/phase-10-validation.md).
