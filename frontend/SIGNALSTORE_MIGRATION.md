# SignalStore migration report

## Features discovered before implementation

A complete frontend source search found four classic Store features and no other feature stores:

| Feature      | Previous state/effects                                                                                                     | Final ownership                                                                                                                                                           |
| ------------ | -------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Auth/session | auth actions, reducer/generated selectors, navigation effects; SessionService; login, guards, interceptor, shell/dashboard | AuthStore holds the same memory-only session fields and computed ADMIN/CUSTOMER roles. Methods handle login success/failure, restoration, logout and reset.               |
| Quotes       | quote actions, reducer/generated selectors, create/history/detail/navigation effects                                       | QuoteStore owns creation, selected snapshot, paginated history, request statuses and safe errors.                                                                         |
| Audit        | actions, reducer/generated selectors and effects in audit.store.ts                                                         | AuditStore owns applied query, paginated result, detail, loading and safe errors. Filter controls remain Reactive Forms; query-param synchronization stays in components. |
| Operations   | actions, reducer/generated selectors and effects in operations.store.ts                                                    | OperationsStore owns health/metrics snapshot, loading and errors. Manual and pausable 15-second refresh remain implemented; page exit cancels refresh.                    |

## Architecture and preserved behavior

Four independent root-provided `signalStore` definitions use `withState` and `withMethods`.
Components/templates read state signals directly. Computed role signals replace derived role
selectors; generated field selectors become direct state signals. `withHooks` handles request
cancellation and reset-registration cleanup when protected stores are destroyed.

Quote/audit API effects become `rxMethod` workflows. History/detail/filter changes retain
`switchMap` cancellation. Quote creation and operations refresh retain `exhaustMap` overlap
protection. Errors are caught inside each request so future invocations still work; existing
safe error mapping is retained. Creation navigation runs only after a successful API response.

Auth navigation effects become AuthStore methods. Login and registration retain their existing
page-local API workflows, transient credentials, form validation and destruction cancellation.
SessionService remains the Observable boundary needed by the initializer, guards and HTTP
interceptor. Its refresh queue, `shareReplay`, timeouts and logout cookie ordering are preserved;
it now updates AuthStore methods rather than dispatching actions.

The access token remains memory-only; refresh tokens remain HttpOnly cookies. Startup restores
before navigation; expired-session guards wait for restoration. Simultaneous protected 401s
share one refresh and retry each request. Refresh failure clears the session. A 403 never
refreshes. POST login/register/refresh/logout are excluded; Gateway origin/path matching and
late-401 newer-token retry behavior are preserved. ADMIN/CUSTOMER claims restore unchanged.

SessionResetService is a synchronous callback registry with no domain state. Logout, login
failure, new login and explicit auth reset cancel all instantiated protected-store requests and
clear quotes, audit and operations state. Each feature also exposes its own reset method.
Cancellation occurs before clearing data and does not terminate the rxMethod input, so a later
session can load normally. Ordinary token restoration does not clear feature state or cancel
the protected requests queued behind refresh.

Reactive Forms remain responsible for form values and validation. Local Signals remain
responsible for UI-only state, including catalog results, validation messages, expansion and
auto-refresh controls. Backend-calculated premiums, API DTOs, URLs, routing and role guards are
preserved. A cancelled creation request cannot undo an already completed server transaction.

## Infrastructure and dependencies

- Added exact `@ngrx/signals` 22.0.1 and updated the lockfile.
- Removed `@ngrx/store` and `@ngrx/effects` after confirming no remaining consumers.
- Removed global Store, feature-state and Effects providers. Router, HTTP/interceptor and app initializer providers remain.
- Deleted the six auth/quote action, reducer and effect files; replaced inline audit/operations classic infrastructure.
- No remaining classic NgRx usage in frontend source, dependency manifest or lockfile.
- No backend, API contract or infrastructure changes.

## Validation

- `npm test`: 130 tests across 13 files pass, with no unhandled errors.
- `npm run build`: production build passes.
- `git diff --check`: passes.
- Source/manifest/lockfile scan finds no classic Store/Effects APIs or imports.
- Changed-file inspection confirms all changes are under `frontend/`.

Reducer/selector/effect tests were migrated to SignalStore domain behavior tests. Auth tests
cover stored fields, role computation, navigation, restoration and clearing. Existing session,
guard and interceptor tests retain refresh success/failure, concurrent 401 retry, exclusions,
403 behavior and stale-refresh-after-logout coverage. Quote tests cover API creation, history,
detail, pagination, safe failures/retry, overlap prevention, latest reads and session reset.
New audit store tests cover applied filters/pagination, detail, pending/error states, retries,
superseded reads and cancellation/reset. Operations tests retain rendering, safe errors,
manual/automatic refresh, no overlap, page-exit cancellation and all session-reset cases.
UI tests may seed presentation fixtures with NgRx's testing-only `unprotected` helper;
production stores retain protected state and expose domain methods.

## File inventory

Paths below are relative to `frontend/`. Updated README documents the state ownership rules,
rxMethod concurrency, computed signals, refresh coordination and synchronous logout reset.

### Created

- `SIGNALSTORE_MIGRATION.md`
- `src/app/store/audit/audit.store.spec.ts`
- `src/app/store/auth/auth.store.ts`
- `src/app/store/quotes/quote.store.ts`
- `src/app/store/session-reset.service.ts`

### Modified

- `README.md`
- `package-lock.json`
- `package.json`
- `src/app/app.component.spec.ts`
- `src/app/app.component.ts`
- `src/app/app.config.ts`
- `src/app/core/auth/session.service.spec.ts`
- `src/app/core/auth/session.service.ts`
- `src/app/core/guards/admin.guard.ts`
- `src/app/core/guards/auth.guard.spec.ts`
- `src/app/core/guards/auth.guard.ts`
- `src/app/core/guards/customer.guard.ts`
- `src/app/core/guards/refresh-guard-session.ts`
- `src/app/core/interceptors/auth.interceptor.spec.ts`
- `src/app/core/interceptors/auth.interceptor.ts`
- `src/app/core/quotes/quote-api.spec.ts`
- `src/app/features/audit/audit-detail.component.html`
- `src/app/features/audit/audit-detail.component.ts`
- `src/app/features/audit/audit-list.component.html`
- `src/app/features/audit/audit-list.component.ts`
- `src/app/features/audit/audit.spec.ts`
- `src/app/features/auth/auth-pages.spec.ts`
- `src/app/features/auth/login/login.component.ts`
- `src/app/features/dashboard/dashboard.component.ts`
- `src/app/features/operations/operations.component.html`
- `src/app/features/operations/operations.component.ts`
- `src/app/features/operations/operations.spec.ts`
- `src/app/features/quotes/detail/quote-detail.component.ts`
- `src/app/features/quotes/history/quote-history.component.ts`
- `src/app/features/quotes/new/quote-new.component.ts`
- `src/app/features/quotes/quote-pages.spec.ts`
- `src/app/store/audit/audit.store.ts`
- `src/app/store/auth/auth.spec.ts`
- `src/app/store/operations/operations.store.ts`
- `src/app/store/quotes/quote.spec.ts`

### Deleted

- `src/app/store/auth/auth.actions.ts`
- `src/app/store/auth/auth.effects.ts`
- `src/app/store/auth/auth.reducer.ts`
- `src/app/store/quotes/quote.actions.ts`
- `src/app/store/quotes/quote.effects.ts`
- `src/app/store/quotes/quote.reducer.ts`
