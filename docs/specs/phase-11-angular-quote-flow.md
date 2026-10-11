# Phase 11 — Angular Quote Flow

Status: Complete; see [validation](../phase-11-validation.md). Source: approved Phase 11 request.

## Scope

CUSTOMER routes `/quotes/new`, `/quotes`, `/quotes/:id`; Reactive Forms for quote input, local Signals for catalog/loading and breakdown visibility, NgRx for shared creation/detail/history results and asynchronous request outcomes. Quote Effects own API calls and creation navigation. Workflow status/errors live only in Store and components read them as Signals; catalog state remains component-only. No form controls in Store.

Gateway is the only frontend API origin. Reuse POST/GET Quote contracts and immutable stored snapshots. Add only authenticated GET `/api/v1/vehicles/brands` and `/api/v1/vehicles/brands/{brandId}/models` backed by active Quote-owned reference rows, read-only transactions, DTO responses and stable name/ID ordering. Missing/inactive brands return the same safe 404. No migrations or new tables.

## Behavior and security

Quote routes/navigation require CUSTOMER; ADMIN-only users return to dashboard. Backend remains authoritative. Clear quote state on logout, failed authentication or new login; cancel pending effects when the session changes so another account cannot receive old results. Creation uses exhaustMap to prevent concurrent duplicates; list/detail use switchMap to discard superseded requests. Do not retry POST automatically. Changing brand clears model and cancels previous model loading.

Use backend pagination, default page 0/size 20, previous/next. Never send customerId. Render stored driver/vehicle/pricing data, without catalog refresh on detail or premium calculation. Safe 400/404/502/503 errors; existing auth interceptor handles 401. Accessible form labels, inline errors, loading states and responsive layout.

## Definition of Done

Frontend unit/component/effect/API tests, npm ci/test/build; backend full Maven regression and relevant PostgreSQL reference-query checks. Browser register/login, catalog, creation, detail, history/paging, logout. Clean temporary data/processes and verify no outbox/Kafka changes. Document commands, state ownership and limitations. No Phase 12 messaging, pricing admin UI, edits/deletes, refresh tokens or persistent authentication.
