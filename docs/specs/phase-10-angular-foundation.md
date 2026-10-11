# Phase 10 — Angular Foundation

Source: approved Phase 10 request. Scope: frontend foundation only. Preserve backend APIs, migrations, infrastructure, and existing tests. No Phase 11 quote UI.

## Goal and routes

Standalone Angular application with `/login`, `/register`, and protected `/dashboard`. Root and wildcard resolve to dashboard when authenticated, otherwise login. Guest-only auth pages redirect an already authenticated session to dashboard. API calls use the configured Gateway base URL only (local http://localhost:8080); development origin is http://localhost:4200, supported by existing CORS.

## State boundaries

Reactive Forms own field values and validation. Component Signals own loading, safe errors, and password visibility; computed Signals express local UI derivations. NgRx Store owns the memory-only access token, user ID, roles, authentication flag, and expiration timestamp. Components use selectSignal for shared state. No Signal Store, other global state library, form controls, or local page flags in NgRx.

Login calls AuthApiService from the component so plaintext credentials never enter NgRx actions/state. Its successful response is decoded for UI claims, then a password-free loginSuccess action updates the store. loginFailure clears auth without storing error text. NgRx Effects handle navigation after loginSuccess/logout. Registration calls AuthApiService directly and redirects to login with a success indication; it does not authenticate.

## Auth behavior

Registration sends firstName, lastName, email, password through Gateway. Reactive validation covers required/nonblank fields, email, backend lengths, registration minimum 12 characters, and 72 UTF-8 password bytes. Login uses required email/password and compatible maximums. Transient passwords exist only in the form/request during submission, are cleared afterward, and are never persisted or logged.

JWT payload decoding handles malformed input and reads sub/roles/exp for UI only. It performs no cryptographic verification and includes no signing key. Gateway/services remain authoritative. Memory-only state is lost on browser refresh; route guards send the user to login. No storage persistence, backend logout, token refresh, retries, or refresh queue.

## Interceptor and guards

Attach Bearer only to configured Gateway `/api/` requests, excluding POST login/register. Do not leak tokens to other origins or similarly prefixed URLs. A protected Gateway 401 clears the matching current session and navigates to login; public auth failures remain local and do not cause redirect loops. Ignore a stale request's 401 when a different session token has since been installed. Expired sessions are cleared when a guard/protected API request checks them; no background refresh or expiry scheduler.

Dashboard guard uses NgRx auth state and expiry for UX; it is not backend security. Logout clears all shared session fields and navigates to login through an effect. Dashboard shows simple CUSTOMER/ADMIN text, without quote/admin business UI.

## Tests and Definition of Done

Normal frontend tests use Angular HTTP testing/mocks, covering forms/local Signals, safe errors, register/login requests, reducer/selectors, navigation effects, malformed/expired JWT payloads, token attachment boundaries/401 handling, route guards, and role rendering. npm ci, the actual test script, and production build must pass. No backend required by normal tests.

Live browser validation uses existing Gateway/Auth: temporary registration, duplicate email, wrong password, login/dashboard, shared state, authenticated header behavior, logout/guard, and reload logout. Clean up the temporary account and any dev server started for validation. Backend production changes require a demonstrated interoperability issue; root/backend edits require Maven regression verification. Documentation explains tooling, commands, API configuration, state boundaries, and deferred work.
