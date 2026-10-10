# Phase 9 — Quote Retrieval and Ownership

Source: approved Phase 9 request. Scope: Quote retrieval and Gateway regression coverage; retain Phase 8 creation, schemas, seeds, and infrastructure. No Phase 10 implementation.

## Goal and endpoints

- GET `/api/v1/quotes/{id}` returns one stored QuoteResponse.
- GET `/api/v1/quotes?page=0&size=20` returns QuotePageResponse: content, page, size, totalElements, totalPages.
- POST `/api/v1/quotes` remains CUSTOMER-only and unchanged in behavior.

Quote independently validates the existing HS256 token contract. GET requires CUSTOMER or ADMIN. Customer identity comes exclusively from UUID JWT sub; the ADMIN decision comes from validated ROLE_ADMIN authority. A token with both authorities uses ADMIN retrieval access. Gateway requires authentication and forwards the token through the existing quote route; it contains no ownership logic.

## Ownership and errors

CUSTOMER detail uses findByIdAndCustomerId. CUSTOMER list applies customer_id in both the ID-page query and snapshot-fetch query. ADMIN detail/list may read any owner. Cross-customer and missing detail requests return identical 404 QUOTE_NOT_FOUND bodies, without owner information. Missing/invalid JWT is 401; valid unsupported authority is 403. Invalid UUID path is 400 INVALID_QUOTE_REQUEST.

Only page and size list parameters are supported, for both roles. Defaults: page 0, size 20; size 1–100. Reject negative pages, malformed/blank/overflowing numbers, offsets beyond the SQL/JPA integer limit, duplicate parameters, and unknown parameters (including customerId, status, sort) with 400 INVALID_PAGE_REQUEST. No optional admin filters in this phase. Beyond-last pages return empty content with total metadata.

## Query and transaction strategy

QuoteRetrievalService owns read-only transactions and has no dependency on Pricing, vehicle repositories, or write services. Map entities to DTOs while the transaction is open; open-in-view remains disabled.

For detail, an entity graph loads driver, vehicle, pricing, and pricing.adjustments in one SQL query. For lists, first page scalar quote IDs ordered by created_at DESC, id DESC, with a matching ownership-scoped count query. Then fetch all snapshots for those IDs using one unpaged entity-graph query. Restore the ID-page order in Java because IN does not preserve order. Empty pages skip the snapshot query.

Collection fetching never participates in a paginated SQL query. Expected bounded cost is at most three SELECTs per list page (ID page, count when needed, snapshots), not one query per quote/relationship. Existing customer_id/created_at index is retained; no new migration or index. Offset pagination is deterministic for a fixed dataset but is not a frozen browsing session across concurrent inserts.

## Historical response and status

Reuse QuoteResponse.from from Phase 8. Read only persisted driver, vehicle names/IDs, monetary values, currency, timestamps, and ordered adjustments. No current catalog lookup, pricing-rule lookup, recalculation, or HTTP Pricing call. Existing unsupported rule-version/base-rule-ID fields remain omitted because the schema cannot store them.

Return stored PRICED, FAILED, and EXPIRED values without transitions. An elapsed expiresAt does not change PRICED into EXPIRED. Optional missing snapshots are represented as null, allowing existing FAILED rows without completed snapshots to be read. No failed-quote lifecycle is introduced.

## Testing and Definition of Done

Normal HTTP tests cover JWT failures, roles, own/cross-owner/missing detail, identical 404s, customer/admin lists, pagination defaults/bounds/unknown parameters, ordering restoration, snapshot mapping, and no Pricing/catalog/write calls. Existing creation behavior remains tested; replace only the obsolete Phase 8 assertion that GET must be denied. Gateway tests verify GET authentication and original token/path/query forwarding for both roles.

Disposable PostgreSQL tests exercise actual ownership queries, counts, newest-first/UUID tie order, multi-adjustment graph loading, no duplicate roots, query counts, read-only row fingerprints, and catalog-independent historical snapshots. Existing creation/rollback tests remain.

Live validation creates two temporary customers (two quotes for A, one for B) and a temporary administrator through real Auth. Exercise creation and retrieval through a newly built Gateway, also checking Quote directly. Verify read-only behavior, Pricing-independent retrieval after a temporary rule change, cleanup, and unchanged Kafka offsets/outbox/Flyway history. Temporary validation application processes must be stopped afterward; existing user processes remain untouched.

## Deferred

No edit/cancel/delete/reprice/draft endpoint, automatic expiration, Kafka event/listener, outbox publication, Angular, refresh token, asymmetric JWT, service OAuth2, Redis, search, report/export, or Phase 10 work.
