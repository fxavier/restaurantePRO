# PRD R3 — Stage 3 "Controlo avançado" (backend)

Status: **APPROVED** 2026-11-05, together with [r3-decisions.md](../design/r3-decisions.md) (cited as Dn and Pn). Earlier decisions: [backend-decisions.md](../design/backend-decisions.md), [r2-decisions.md](../design/r2-decisions.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md). Conventions: [backend-conventions.md](../architecture/backend-conventions.md).

Base package `com.xavier.backend`, per-module layout D12, Flyway blocks D93. Every slice is built with TDD: no `@Disabled`, and `cd backend && rtk mvn clean test` must be green at exit with **0 skipped**.

- **Every slice PR targets `main`, and none is stacked.** A slice branches from `main` once every slice it is blocked by has merged.
- Slices merge one at a time, in ascending slice number within a group, with the full suite after each merge.
- Slices **do not commit `docs/architecture/modules/`** (D44). Only S33 does.
- **No slice edits `pom.xml`.** R3 needs no new Maven dependency. `pg_trgm` is a PostgreSQL extension created by a migration (D72).
- The names below are binding. Internals are the implementer's call.

Conventions for every endpoint below, as in R2:
- **POS:** `requirePos(...)`, with the store and terminal taken from the session.
- **Idem:** `Idempotency-Key` through `access.IdempotentRequests` (`execute`, or `steps` for D63).
- **Approver:** the `X-Approver-Code` and `X-Approver-Pin` headers, verified before the transaction (D40).
- **Errors:** a stale version gives 409. Business rules give 422 `ProblemDetail` with a stable `code`, and every new code is added to `CodedErrorContractTest`.
- **Store scope:** D42 order.

---

## S19 — Platform prefactor (runs alone, first)

**Write scope:**
- `access/`, `shared/` (`notify/`, new `events/`), `audit/` (guard only), `config/`
- `organization/` (`OrganizationApi.findTerminal`), `dining/` (`DiningApi.tableInfo`, controllers → `IdempotentRequests`), `catalog/` (controllers → `IdempotentRequests`), `fiscal/controller/` (→ `IdempotentRequests`)
- `orders/model/event/SubtotalRequested.java` and `orders/service/SubtotalService.java`, `identity/` (`EmployeeDirectory`, V3010)
- `src/test/.../architecture`, `db/migration/shared/V1006`, `db/migration/identity/V3010`, `application.yaml`, `docs/architecture/backend-conventions.md`

| contract | detail |
|---|---|
| `access.Permission` | Appends the D92 constants. `V3010__identity_r3_permissions.sql` grants them to the administrator role and to the default roles in the D92 table. |
| `access.AccessPolicy` | `void requireInAnyStore(Permission p)`: passes when `p` is held installation-wide or in at least one store, otherwise 403. `AccessScope scopeOf(Permission p)`, where `record AccessScope(boolean global, Set<UUID> stores)`: 403 when it is empty. Logged like `require`. |
| `organization.OrganizationApi` | `Optional<TerminalSummary> findTerminal(UUID terminalId)`, with `TerminalSummary(id, storeId, code, name, active)` (R2 backlog). |
| `dining.DiningApi` | `Optional<TableInfo> tableInfo(UUID tableId)`, where `TableInfo(tableId, tableCode, areaId, areaCode, areaName)` (readOnly). |
| `identity.EmployeeDirectory` (root) | `Map<UUID, EmployeeSummary> summaries(Collection<UUID> ids)`, where `EmployeeSummary(id, code, displayName)` (readOnly). It is used for export labels. |
| `orders.model.event.SubtotalRequested` | Gains `UUID requestId` as its **last** component. `SubtotalService` draws a UUIDv7 per command. A stored R2 publication deserializes with `requestId = null`. |
| `shared.notify.ChangeNotifier` | Implements `SmartLifecycle`: `stop()` completes every emitter, and its phase stops it before the web server's graceful shutdown (D89 (3)). |
| `shared.events.EventPublicationRecovery` | Scheduled: resubmits incomplete publications older than `restaurantpro.events.resubmit-older-than` (PT5M) with fewer than `max-attempts` (10) attempts, every `resubmit-interval` (PT1M). Daily purge of completed publications older than `completed-retention` (P30D) (D88). |
| `shared.events.EventPublicationStats` | `long pendingFor(String listenerIdPrefix)`; gauges `event_publications{state, module}`. |
| `config.SystemEventPublicationController` | `GET /api/v1/backoffice/system/event-publications?state=INCOMPLETE\|FAILED&page`; `POST /system/event-publications/resubmit {publicationIds[] \| olderThan}`. Both need `SYSTEM_MONITOR`; the POST is audited (system-wide, no store). |
| `audit.AuditLog` | The secret-key guard walks nested maps and lists (D82; closes the ADR 0005 known limit). |
| Idempotency hygiene | Catalog, dining and fiscal controllers use `access.IdempotentRequests` (R2 backlog). Behaviour is unchanged: existing tests stay green. |
| Migration `V1006__shared_event_publication_pending_ix.sql` | `CREATE INDEX … ON event_publication (listener_id) WHERE completion_date IS NULL`. |
| Architecture tests | New forbidden edges (D65) with `allowEmptyShould(true)`: `fiscal ↛ customers`, `customers ↛ {orders, settlement, fiscal, cash, production}`, nothing → `reporting`. New rule `reportingCallsOnlyReadOnlyFacadeMethods`. Each has an `ArchitectureRulesFireTest` fixture. |

**Tests:**
- `AccessPolicyTest`: any-store pass and deny; scope global and per store.
- `EventPublicationRecoveryTest`: a listener that fails once is completed by the job with no restart; after 10 attempts it stays FAILED; the gauge counts it.
- `ChangeNotifierShutdownTest`: a context close with an open emitter takes less than 2 s.
- `AuditLogTest`: a nested `pin` key is rejected.
- `SubtotalRequestIdTest`: two commands produce two ids; a replay of the same key publishes nothing new.

**Acceptance:** half of A29 (runtime resubmission), the SSE half of A30, the rule half of A32. The full R2 suite stays green.

---

## Group 1 (after S19, in parallel): S20 ∥ S21 ∥ S22 ∥ S23 ∥ S24 ∥ S25 ∥ S26 ∥ S27

### S20 — Customers and credit ledger
**Write scope:** `customers/` (new module, `package-info` per D65), `db/migration/customers/V11001+`.

**Migrations:**
- `V11001__customers_create_customer.sql`:
  - `CREATE EXTENSION IF NOT EXISTS pg_trgm`
  - `customer(id, name, name_search GENERATED (lower(translate(name, <accent map>))) STORED, tax_id, email, notes, active, created_at, updated_at, version)`
  - GIN `(name_search gin_trgm_ops)` and a B-tree on `tax_id`
- `V11002__customers_create_phone_and_address.sql`:
  - `customer_phone(id, customer_id FK, digits CHECK '^[0-9]{4,20}$', digits_reversed GENERATED (reverse(digits)) STORED, label, is_primary)`, with `UNIQUE(customer_id, digits)` and B-tree `(digits_reversed text_pattern_ops)`
  - `customer_address(id, customer_id FK, kind CHECK IN ('BILLING','DELIVERY'), line1, line2, city, postal_code, country, notes, version)`
- `V11003__customers_create_credit.sql`:
  - `credit_account(customer_id PK FK, credit_limit NUMERIC(19,4) CHECK ≥ 0, currency, credit_blocked, blocked_reason, blocked_at, version, created_at)`
  - `credit_limit_change(id, customer_id, old_limit, new_limit, reason NOT NULL, actor_kind, actor_id, at)`: append-only
  - `credit_reservation(id, customer_id, amount > 0, currency, charge_id UNIQUE, status CHECK IN ('ACTIVE','CONSUMED','RELEASED'), created_at, closed_at)`, with a partial index `(customer_id) WHERE status='ACTIVE'`
  - `credit_ledger_entry(id, customer_id, kind CHECK IN ('DEBIT','CREDIT'), amount > 0, currency, source_type CHECK IN ('CREDIT_CHARGE','PAYMENT','REFUND'), source_id, document_id, store_id, business_date, description, occurred_at)`, with `UNIQUE(source_type, source_id, kind)` and index `(customer_id, occurred_at) INCLUDE (kind, amount)`: append-only
  - `credit_settlement(id, credit_entry_id FK, debit_entry_id FK, amount > 0)`, with `UNIQUE(credit_entry_id, debit_entry_id)`: append-only
  - Deferred constraint triggers: Σ settlement per debit ≤ the debit amount, and Σ per credit = the credit amount

**`CustomersApi`** (root). Writes are `MANDATORY`; settlement calls them under the D76 lock order.
- `CustomerSnapshot snapshot(UUID customerId)`: `(customerId, name, taxId, billingAddress)`; 404 when missing, 422 `CUSTOMER_INACTIVE`.
- `CreditView credit(UUID customerId)`: `(limit, balance, reserved, available, blocked)` (readOnly).
- `ReservationView reserve(UUID customerId, Money amount, UUID chargeId)`: locks `credit_account FOR UPDATE`. It is idempotent per `chargeId`. 422 `CREDIT_ACCOUNT_MISSING`, `CREDIT_BLOCKED`, `CUSTOMER_INACTIVE`, `CREDIT_LIMIT_EXCEEDED` (with the `available` property).
- `void release(UUID chargeId)`: ACTIVE → RELEASED. No-op when already released.
- `LedgerEntryView postDebit(UUID chargeId, UUID documentId, UUID storeId, LocalDate businessDate, Money amount)`: consumes the reservation. It is idempotent per charge.
- `LedgerEntryView postCredit(UUID paymentId, UUID customerId, Money amount, List<UUID> debitEntryIds, UUID storeId, LocalDate businessDate)`: FIFO when the list is empty; 422 `AMOUNT_EXCEEDS_DEBT`.
- `LedgerEntryView reverseCredit(UUID refundId, UUID paymentId, Money amount)`: a DEBIT with source REFUND that reopens the settled debits, latest first.
- `Money debtOf(UUID customerId)` (readOnly).

**Endpoints:**

| method & path | permission | idem | notes |
|---|---|---|---|
| `GET /api/v1/pos/customers?q=` | `CUSTOMER_VIEW` | — | Always returns a candidate list (≤ 20). At least 4 digits or 3 characters, else 400. Never auto-selects (D72). |
| `POST /api/v1/pos/customers` | `CUSTOMER_MANAGE` | yes | Quick create (name, phone, tax id) |
| `GET /api/v1/pos/customers/{id}`, `/credit` | `CUSTOMER_VIEW` | — | Available credit |
| `GET/POST /api/v1/backoffice/customers`, `GET/PUT /customers/{id}`, `…/phones`, `…/addresses` | `CUSTOMER_VIEW` / `CUSTOMER_MANAGE`, in any store | PUT `@Version` | |
| `GET /api/v1/backoffice/customers/{id}/statement?from&to` | `CUSTOMER_VIEW` | — | Entries with running balance, document refs, settlements |
| `GET /api/v1/backoffice/customers/debts?asOf&minBalance&page` | `CUSTOMER_VIEW` | — | Aging buckets 0–30 / 31–60 / 61–90 / > 90 by debit age |
| `POST /api/v1/backoffice/customers/{id}/credit-limit-changes {newLimit, reason}` | `CREDIT_LIMIT_MANAGE` | yes | Creates the account when it is absent. Audited. |
| `POST /customers/{id}/credit-block {reason}`, `/credit-unblock` | `CREDIT_LIMIT_MANAGE` | yes | Audited |

**Tests:**
- `CustomerSearchTest`: suffix, contains and tax id; one match is still a list; `EXPLAIN` names each index, after `ANALYZE` on 5 000 seeded rows.
- `CreditLimitRaceTest` (the `reserve` half): two threads, limit 100, 60 + 60 → one succeeds.
- `CreditLedgerTest`: FIFO, explicit debits, reversal, a repeated post adds nothing, and the deferred sums.
- `CreditFactsImmutableTest`.

**Acceptance:** A31, the customers half of A20 and A21.

### S21 — Cash R3
**Write scope:** `cash/`, `db/migration/cash/V8007+`.

**Migrations:**
- `V8007__cash_movement_refund_adjustment.sql`:
  - Type CHECK + `REFUND` (OUT) and `ADJUSTMENT` (IN or OUT)
  - Source CHECK: REFUND ⇒ `source_type='REFUND'`
  - Method CHECK: `REFUND` and `ADJUSTMENT` may use any method
  - `actor_account_id`, with `actor_employee_id` made nullable and a CHECK that exactly one is set
  - The open-session trigger lets `ADJUSTMENT` through on CLOSED sessions whose day is not FINANCIALLY_CLOSED
  - Reason required for ADJUSTMENT
- `V8008__cash_session_close_kind.sql`: `closed_by_employee_id`, `close_kind CHECK IN ('OPERATOR','FORCED')`, `close_reason` (FORCED ⇒ NOT NULL).
- `V8009__cash_day_reopen_from_status_and_closing_round.sql`:
  - `cash_day_reopening.from_status`
  - `cash_closing.day_round INT NOT NULL DEFAULT 0`
  - Drop `cash_closing_live_uk` and recreate it on `(register_id, cash_day_id, day_round)`

**`CashApi` additions.** The writes are `MANDATORY`.
- `MovementView recordRefund(RefundCommand cmd)`, where `RefundCommand(UUID sessionId, String methodCode, Money amount, UUID refundId, UUID actorEmployeeId)`. It is idempotent per `refundId`. 422 `SESSION_NOT_OPEN`.
- `void closeSessionForced(UUID sessionId, long expectedVersion, List<MethodCount> counts, UUID closerEmployeeId, String reason)`
- `SessionDay sessionDay(UUID sessionId)`: `(cashDayId, businessDate, status)` (readOnly; reporting, D85).
- `Optional<DayView> findDay(UUID storeId, LocalDate businessDate)` (readOnly; finality, D86).

**Endpoints and rules:**

| method & path | permission | idem | notes |
|---|---|---|---|
| `POST /api/v1/backoffice/cash/sessions/{id}/adjustments {direction, methodCode, amount, reason}` | `CASH_ADJUST@store` | yes | 422 `SESSION_STILL_OPEN` (use a normal movement), `DAY_FINANCIALLY_CLOSED`. Audited. |
| `POST /api/v1/backoffice/cash/closings/{id}/approve` | unchanged | yes | Re-computes the expected amount: if it changed → 409 `CLOSING_STALE` |
| `POST /api/v1/backoffice/cash/days/{id}/reopen {reason}` + Approver | from OPERATIONALLY_CLOSED: as in R2; from FINANCIALLY_CLOSED: `CASH_DAY_REOPEN`, with an approver holding `CASH_CLOSING_APPROVE` | yes | The reopening records `from_status`. Later closings get `day_round + 1`. |
| `POST /api/v1/pos/cash/sessions` | unchanged | yes | P28: after the cutoff, while an earlier day is OPEN, it opens in the new day with `warnings:[PREVIOUS_DAY_OPEN]`. An earlier or reopened day → 422 `DAY_CLOSING_ONLY` (P25). |
| `POST /api/v1/pos/cash/movements` (type EXPENSE) | P24: `CASH_OPERATE` plus an approver holding `CASH_EXPENSE` (D40) | yes | 422 `SELF_APPROVAL` |
| `GET /api/v1/backoffice/cash/reconciliation?storeId&from&to` | `CASH_VIEW@store` | — | Per day × register × method: expected, counted, adjustments, difference, justification, status, round |

**Tests:**
- `CashAdjustmentTest`
- `ClosingStaleTest`
- `FinancialReopenTest`: the APPROVED round 0 is kept, and round 1 is approvable.
- `NewDayPolicyTest`: across the cutoff, including DST.
- `ExpenseApprovalTest`
- `RefundMovementTest`: one row per `refundId`.
- `ForcedCloseCashTest`

**Acceptance:** the cash half of A26 and the movement half of A23.

### S22 — Fiscal R3
**Write scope:** `fiscal/` (except the controllers' idempotency, done in S19), `db/migration/fiscal/V10007+`.

**Migration `V10007__fiscal_partition_source_and_on_account.sql`:**
- `source_type` CHECK + `PARTITION`.
- New columns: `order_id`, `on_account BOOLEAN NOT NULL DEFAULT false`, `customer_id`, `business_date`.
- `order_ref UUID GENERATED ALWAYS AS (coalesce(order_id, CASE WHEN source_type='ORDER' THEN source_id END)) STORED`, with index `(order_ref)`.
- CHECK: PARTITION ⇒ `order_id` NOT NULL; `on_account` ⇒ `customer_id` NOT NULL.
- The R2 immutability trigger is untouched, and no row is updated.

**`FiscalApi` changes:**
- `DocumentView createDraft(DraftCommand cmd)` (`MANDATORY`), where `DraftCommand(UUID storeId, SourceType sourceType, UUID sourceId, UUID orderId, LocalDate businessDate, Buyer buyer, UUID customerId, boolean onAccount, List<DraftLine> lines, Money totalGross, Money totalNet, Money totalTax)`.
  - It is idempotent per `(sourceType, sourceId, SALE)`. An existing DRAFT may have its buyer, customer and `onAccount` updated. An ISSUED document is returned unchanged.
  - `createSaleDraft(SaleDraftCommand)` remains as a delegate for R2 callers.
- `Coverage coverage(UUID orderId)` (readOnly), where `Coverage(List<CoveredLine(UUID sourceLineId, BigDecimal quantity, Money gross, Money tax)> issued, List<…> draft, List<UUID> draftDocumentIds)`.
- `List<DocumentView> documentsOfOrder(UUID orderId)` (readOnly).
- `DocumentFacts documentFacts(UUID documentId)` (readOnly, for reporting): header (store, business date or its fallback, `issued_at`, terminal, issuer, customer, on-account, series and number, non-fiscal, totals), lines with `sourceLineId`, and the tax summary.
- `Page<UUID> issuedDocumentIds(UUID storeId, LocalDate fromBusinessDate, LocalDate toBusinessDate, Pageable page)` (readOnly, for backfill).
- `IssueRequest` gains `boolean onAccount`. The internal adapter prints "A CRÉDITO" on an on-account document.

**Endpoint changes:** `GET /api/v1/backoffice/fiscal/documents` gains the filters `orderId` and `customerId`.

**Tests:** `PartitionDraftTest`, `CoverageTest`, `R2DocumentOrderRefTest` (an R2-shaped row gets `order_ref`), `GaplessNumberingTest` (still green), `OnAccountPrintoutTest`.

**Acceptance:** the document half of A16 and A20.

### S23 — Orders R3
**Write scope:** `orders/`, `db/migration/orders/V6008+` (only if an index is needed).

**`OrdersApi` changes:**
- `OrderBillingView` gains `tableId` and `tableCode`. `BillingLine` gains `itemId`, `itemCode`, `releaseId`, `servedByEmployeeId` (the batch confirmer), `confirmedAt` and `taxCode` (if absent).
- `VoidView voidLine(SettledVoidCommand cmd, UUID approverEmployeeId)` (`MANDATORY`), where `SettledVoidCommand(UUID orderId, long expectedVersion, UUID lineId, BigDecimal quantity, String reason, boolean wasted, UUID operatorEmployeeId)`.
  - **Contract:** the caller holds `order_settlement` and checked headroom (D71). The method ignores the PAYMENT lock and still refuses INVOICE (422 `ORDER_FROZEN`).
  - It publishes `OrderLinesVoided`, writes the void ticket path, and is audited exactly like the R2 void.
- `VoidFacts voidFacts(UUID voidId)` (readOnly), where `VoidFacts(voidId, orderId, storeId, businessDay, at, lineSnapshot, quantity, gross, reason, wasted, operatorEmployeeId, approverEmployeeId, afterDispatch)`.
- `Page<UUID> voidIds(UUID storeId, LocalDate from, LocalDate to, Pageable p)` (readOnly).

**Rules:**
- P27: `POST /orders/{id}/subtotal/print` on a frozen order → 422 `DOCUMENT_ISSUED`.
- The R2 `POST /orders/{id}/voids` is unchanged.

**Tests:** `SettledVoidApiTest` (PAYMENT lock bypassed, INVOICE refused, event published), `SubtotalAfterIssueTest`, `BillingViewR3Test`.

**Acceptance:** the orders half of A24.

### S24 — Production R3
**Write scope:** `production/`, `db/migration/production/V7006+`.

**Migration `V7006__production_unrouted_tickets.sql`:**
- `routing CHECK IN ('ROUTED','UNROUTED') NOT NULL DEFAULT 'ROUTED'`
- `printer_id` made nullable, with a CHECK that `routing='UNROUTED'` ⇔ `printer_id IS NULL` (`suppressed` stays false for UNROUTED)
- Partial index `(store_id, created_at) WHERE routing='UNROUTED'`
- Source CHECK + `RECEIPT_SLIP` (D75)

**Rules:**
- A missing route or terminal printer creates an UNROUTED ticket, with the metric and an SSE `PRINT_JOBS` notice.
- The SUBTOTAL ticket dedupes on `requestId` (fallback: the R2 digest).

**Endpoints:**
- `GET /api/v1/{pos|backoffice}/production-tickets?routing=UNROUTED` (`PRODUCTION_VIEW`).
- `POST /api/v1/{pos|backoffice}/production-tickets/{id}/route` (`PRODUCTION_OPERATE`, Idem): CAS UNROUTED → ROUTED and one job. 422 `STILL_UNROUTED`. Audited.

**Not in S24:** the `ReceiptSlipRequested` listener. Its event record is defined in S28, so the listener file is added in S29. S24 only adds the `RECEIPT_SLIP` source value, so S29 needs no production migration.

**Tests:** `UnroutedTicketTest` (route later → one job; a redelivered event → still one ticket), `SubtotalDedupeTest`, `PrintQueueTest` (still green).

**Acceptance:** A30 (except SSE).

### S25 — Support access (identity)
**Write scope:** `identity/` (no change to `EmployeeDirectory`, which is done in S19), `db/migration/identity/V3011+`.

**Migration:** `user_account.support BOOLEAN NOT NULL DEFAULT false`; `support_grant(id, account_id, valid_from, valid_until, reason, granted_by_account_id, revoked_at, revoked_by_account_id, expired_at, created_at)`, with CHECK `valid_until - valid_from <= interval '24 hours'` and a partial unique `(account_id) WHERE revoked_at IS NULL AND expired_at IS NULL` (one ACTIVE grant per account). The expiry job sets `expired_at`, and login also checks `valid_until > now`.

**Endpoints:**
- `GET/POST /api/v1/backoffice/support-grants {accountId, durationMinutes ≤ 1440, reason}` and `POST /support-grants/{id}/revoke` (`SUPPORT_ACCESS_MANAGE`, Idem, audited).
- Login of a support account without an active grant → 401 (the same body as a bad password). The session max-inactive is capped at the remaining time. `SupportGrantExpiry` (each minute) revokes sessions at expiry, audited as SYSTEM.

**Tests:** `SupportAccessTest`.

**Acceptance:** A34.

### S26 — Audit query
**Write scope:** `audit/` (new `controller/`, `service/`), `db/migration/audit/V1103+`.

**Migration:** `V1103__audit_store_action_ix.sql`: `(store_id, action, occurred_at)`.

**Endpoints:**

| method & path | permission | notes |
|---|---|---|
| `GET /api/v1/backoffice/audit/events?storeId&from&to&actorId&actorKind&action&entityType&entityId&page&size` | `AUDIT_VIEW` (scope D82) | 422 `RANGE_TOO_LARGE` when the span is over 92 days; 400 when `from`/`to` are missing |
| `GET /api/v1/backoffice/audit/events/{id}` | `AUDIT_VIEW` on the row's store (D42 (b)) | |
| `GET /api/v1/backoffice/audit/entities/{type}/{id}` | `AUDIT_VIEW` | Filtered to the visible stores |
| `POST /api/v1/backoffice/audit/exports {filters}` → `text/csv` | `AUDIT_EXPORT` + `AUDIT_VIEW` scope | At most 100 000 rows (422 `EXPORT_TOO_LARGE`). Writes `AUDIT_EXPORTED` with the filters. |

**Tests:** `AuditQueryTest` (scope, range, filters, paging), `AuditExportTest`.

**Acceptance:** A27.

### S27 — Deploy: WAL archiving and PITR
**Write scope:** `deploy/`.

**Deliverables:**
- `compose.yaml`: PostgreSQL settings `wal_level=replica`, `archive_mode=on`, `archive_command` copying to the `pg-wal-archive` volume, `archive_timeout=300`.
- `deploy/basebackup.sh`: a nightly `pg_basebackup`, `age`-encrypted off-box copy as in D32.
- `deploy/pitr-restore.sh --target-time <ISO>`: restores into an isolated container.
- `deploy/test-pitr.sh`: write rows, note T, write more, restore to T, and assert that only the rows before T exist.
- Docs in `deploy/README` (RPO and RTO as measured).

**Acceptance:** A33.

---

## Group 2 (after S22 and S23): S28

### S28 — Settlement: payment targets and split
**Write scope:** `settlement/` (all except the `reconciliation/` subpackage), `db/migration/settlement/V9005–V9019`.

**Migrations:**
- `V9005__settlement_payment_targets.sql`:
  - On `payment_attempt`, `payment` and `payment_allocation`: `target_type VARCHAR(15) NOT NULL DEFAULT 'ORDER' CHECK IN ('ORDER','PARTITION','CREDIT_ACCOUNT')`, `partition_id`, `credit_account_id`, with `order_id` made nullable
  - CHECK target ⇔ columns
  - `target_key` stored as a generated column `coalesce(partition_id, order_id, credit_account_id)`
  - Drop `payment_attempt_open_per_order_uk` and create `payment_attempt_open_per_target_uk (target_type, target_key) WHERE status IN ('CREATED','PENDING','UNKNOWN')`
  - Allocation: drop `payment_allocation_payment_uk` and add `UNIQUE(payment_id, target_type, target_id)`; extend `target_type` CHECK
  - Deferred constraint trigger `payment_allocation_sum`
- `V9006__settlement_create_bill_partition.sql`:
  - `bill_partition(id, order_id, store_id, kind CHECK IN ('ITEMS','AMOUNT'), amount (AMOUNT only), group_id, part_no, parts, label, status CHECK IN ('LIVE','CANCELLED'), created_by_employee_id, created_at, cancelled_at, cancel_reason, version)`, with index `(order_id) WHERE status='LIVE'`
  - `bill_partition_line(id, partition_id FK, order_line_id, quantity > 0, gross, net, tax, tax_code, tax_rate, currency)`, with `UNIQUE(partition_id, order_line_id)` and CHECK `gross = net + tax`
  - Line and amount immutability triggers

**`settlement.PartitionAmounts`** (pure, D68):
- `Share share(Money lineGross, Money lineTax, BigDecimal remainingQty, Money remainingGross, Money remainingTax, BigDecimal qty)`
- `List<Money> equalParts(Money residual, int parts)`
- Unit tests and a property test.

**`settlement.SettlementFacts`** (root, readOnly, for reporting):
- `Optional<PaymentFact> paymentFact(UUID paymentId)`, with `PaymentFact(paymentId, storeId, businessDate, at, methodCode, amount, targetType, orderId, partitionId, customerId, collectedByEmployeeId, terminalId)`
- `Optional<RefundFact> refundFact(UUID refundId)`: returns empty until S30
- `Optional<CreditChargeFact> creditChargeFact(UUID chargeId)`: returns empty until S29
- `Page<UUID> paymentIds(storeId, from, to, p)`, `refundIds(…)`, `creditChargeIds(…)`

**Events** (`settlement.model.event`, the contracts):
- `PaymentRecorded(paymentId, storeId)`: published from S28
- `RefundRecorded(refundId, storeId)`: published from S30
- `CreditChargePosted(chargeId, storeId)`: published from S29
- `ReceiptSlipRequested(paymentId, storeId, terminalId, List<String> lines)`: published from S29

**Endpoints** (POS):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/orders/{id}/partitions {orderVersion, kind:"ITEMS", lines[{lineId, quantity}], label?}` → 201 `PartitionView` | `BILL_SPLIT` | yes | 422 `QUANTITY_ALREADY_PARTITIONED`, `QUANTITY_EXCEEDS_NET`, `ORDER_NOT_OPEN`, `ORDER_FROZEN`; 409 stale version. The first partition calls `lockForPayment`. |
| `POST /api/v1/pos/orders/{id}/partitions {orderVersion, kind:"AMOUNT", amount, label?}` | `BILL_SPLIT` | yes | 422 `AMOUNT_EXCEEDS_RESIDUAL` |
| `POST /api/v1/pos/orders/{id}/partitions/equal {orderVersion, parts}` → 201 `List<PartitionView>` | `BILL_SPLIT` | yes | `parts` from 2 to 50; 422 `NOTHING_TO_SPLIT` |
| `GET /api/v1/pos/orders/{id}/partitions` | `ORDER_VIEW` | — | Each with due, paid, credit, balance, financialState, invoiced |
| `POST /api/v1/pos/partitions/{id}/cancel {version, reason}` | `BILL_SPLIT` | yes | 422 `PARTITION_IN_USE` |
| `POST /api/v1/pos/partitions/{id}/documents {buyer?}` → 201 `DocumentView` | `PAYMENT_COLLECT` | `steps` (D63) | ITEMS only (422 `PARTITION_NOT_INVOICEABLE`). s1: lock + `createDraft(PARTITION)`; s2: `issue`. 422 `FISCAL_PENDING` {documentId}. |
| `POST /api/v1/pos/payment-attempts {orderId, partitionId?, methodCode, amount, tendered?}` | unchanged | yes | The balance is per target (D69). 409 `PAYMENT_IN_DOUBT` per target. |
| `POST /api/v1/pos/orders/{id}/settlement/voids {orderVersion, lineId, quantity, reason, wasted}` + Approver | `ORDER_VOID`; approver `ORDER_VOID_APPROVE` | yes | D71: 422 `QUANTITY_PARTITIONED`, `VOID_EXCEEDS_UNPAID`, `CORRECTION_REQUIRES_FISCAL_ADAPTER`, `SELF_APPROVAL` |
| `GET /api/v1/pos/orders/{id}/settlement` | unchanged | — | Gains `partitions[]`, `coverage`, `residualBalance` (and `creditCharges[]`, `refunds[]` once S29 and S30 fill them) |
| `GET /api/v1/pos/settlement/open-orders` | unchanged | — | Gains `tableId` and `tableCode` (R2 backlog) |
| `POST /api/v1/pos/orders/{id}/close` | unchanged | `steps` | D70 coverage sequence |

**Tests:**
- `SplitPreservesTotalsTest` (property)
- `PartitionRaceTest`
- `PartitionDoublePaymentTest`
- `EqualPartsTest`
- `PartitionDocumentTest`, including a new round after a partition document (A19)
- `ResidualDocumentCloseTest`
- `VoidVersusPaymentRaceTest`
- `SettledVoidHeadroomTest`
- `AllocationSumTest`
- `R2PaymentRowsTargetTest` (an R2 row reads as target ORDER)
- `DoublePaymentRaceTest` and `OrderCloseResumeTest` still green

**Acceptance:** A16, A17, A18, A19, the settlement half of A24.

---

## Group 3 (after S28; S29 also needs S20 and S24; S32 also needs S21): S29 ∥ S32

### S29 — Settlement: credit sales and debt payments
**Write scope:** `settlement/` (except `reconciliation/`), `db/migration/settlement/V9020–V9029`, and in production only the new file `production/service/ReceiptSlipListener.java`, its test, and the `settlement :: event` entry in `production/package-info.java` (D65).

**Migration `V9020__settlement_create_credit_charge.sql`:**
- `credit_charge(id, order_id, partition_id, target_type CHECK IN ('ORDER','PARTITION'), target_key GENERATED, store_id, customer_id, amount > 0, currency, status CHECK IN ('RESERVED','POSTED','RELEASED'), document_id, ledger_entry_id, created_by_employee_id, created_at, posted_at, released_at, release_reason, version)`
- Partial unique `(target_type, target_key) WHERE status IN ('RESERVED','POSTED')`
- CHECK POSTED ⇒ document and ledger set
- Index `(status, created_at) WHERE status='RESERVED'`

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/orders/{id}/credit-charges {customerId, amount, partitionId?}` → 201 `CreditChargeView` POSTED | `CREDIT_SALE` | `steps` (D74 s1–s3) | 422 `CREDIT_LIMIT_EXCEEDED`, `CREDIT_BLOCKED`, `CREDIT_ACCOUNT_MISSING`, `DOCUMENT_ALREADY_ISSUED`, `AMOUNT_EXCEEDS_BALANCE`, `FISCAL_PENDING`; 409 `PAYMENT_IN_DOUBT`, `CREDIT_CHARGE_EXISTS` |
| `POST /api/v1/pos/credit-charges/{id}/cancel {reason}` | `CREDIT_SALE` | yes | RESERVED with no ISSUED document only (422 `CHARGE_POSTED`). Releases the reservation. |
| `POST /api/v1/pos/customers/{id}/credit-payments {methodCode, amount, tendered?, debitEntryIds?}` → 201 `AttemptView` | `PAYMENT_COLLECT` | yes | Target CREDIT_ACCOUNT, the R2 flow per method kind, 422 `AMOUNT_EXCEEDS_DEBT`. On SUCCEEDED: `postCredit` + receipt + `ReceiptSlipRequested`. |
| `GET /api/v1/backoffice/settlement/pending?storeId` | unchanged | — | Gains RESERVED charges and partition DRAFT documents |

**Rules:**
- The financial state counts RESERVED and POSTED charges (D74).
- Close requires no RESERVED charge.
- `releasePaymentLock` also requires no live partition and no live charge.
- `CreditChargeSweeper` (scheduled each minute; `SKIP LOCKED`; D46) posts the debit for each RESERVED charge whose document is ISSUED, audited as SYSTEM.
- `SettlementFacts.creditChargeFact` is implemented, and `CreditChargePosted` is published at s3.

**Tests:**
- `CreditSaleTest`: the full flow, 0 movements, 0 Payments, close SETTLED.
- `CreditLimitRaceTest` (end-to-end half).
- `CreditSaleFiscalFailureTest`
- `CreditChargeRecoveryTest`: a second context with a crash after s2.
- `DebtPaymentTest`: FIFO, explicit debits, replay, slip printed once.
- `CreditIsNotCashTest`

**Acceptance:** A20, A21, A22.

### S32 — Reporting
**Write scope:** `reporting/` (new module, `package-info` per D65), `db/migration/reporting/V14001+`.

**Migrations:**
- `V14001__reporting_create_sales_record.sql`:
  - `sales_record(document_id PK, store_id, business_date, local_date, local_hour, issued_at, order_id, partition_id, terminal_id, issued_by_employee_id, customer_id, on_account, series_code, number, non_fiscal, currency, total_gross, total_net, total_tax, projected_at)`
  - `sales_record_line(document_id, line_no, PK both; store_id, business_date, local_hour, item_id, item_code, family_code, description, quantity, gross, net, tax, tax_code, tax_rate, served_by_employee_id, area_id, area_code, mode)`
  - `sales_record_tax(document_id, tax_code, tax_rate, PK; net, tax)`
- `V14002__reporting_create_money_and_void_records.sql`:
  - `payment_record(source_type CHECK IN ('PAYMENT','REFUND'), source_id, PK; store_id, business_date, local_hour, method_code, signed_amount, target_type, order_id, customer_id, employee_id, terminal_id)`
  - `credit_record(charge_id PK, store_id, business_date, customer_id, order_id, amount)`
  - `void_record(void_id PK, store_id, business_date, local_hour, item_code, description, quantity, gross, reason, wasted, after_dispatch, employee_id, approver_id)`
  - `rebuild_run(id, store_id, from_date, to_date, status, started_at, finished_at, error)`
  - Indexes `(store_id, business_date)` on every table, plus `(store_id, business_date, item_code)`, `(…, served_by_employee_id)`, `(…, method_code)`

**Listeners** (`@ApplicationModuleListener`, upsert `ON CONFLICT DO NOTHING`):
- `FiscalDocumentIssued` → `FiscalApi.documentFacts` + `OrdersApi.billingView` lines (served-by, item id, release) + `CatalogApi.snapshot` family (cached per release) + `DiningApi.tableInfo`
- `OrderLinesVoided` → `OrdersApi.voidFacts`
- `PaymentRecorded`, `RefundRecorded`, `CreditChargePosted` → `SettlementFacts`

**Endpoints:**

| method & path | permission | notes |
|---|---|---|
| `GET /api/v1/backoffice/reports/sales?storeId&from&to&groupBy=day\|hour\|terminal\|employee\|area\|item\|family\|mode` | `REPORT_VIEW@store` | `from`/`to` are business dates. Rows `{key, label, documents, quantity, gross, net, tax}`, plus `asOf`, `pendingFacts`, `final` (D86). |
| `GET /reports/taxes?storeId&from&to` | `REPORT_VIEW@store` | Per tax code and rate: net, tax |
| `GET /reports/top-items?storeId&from&to&limit≤100&by=quantity\|gross` | `REPORT_VIEW@store` | |
| `GET /reports/receipts?storeId&from&to&groupBy=method\|employee\|day` | `REPORT_VIEW@store` | `received` (payments − refunds), `onAccount` (credit), `debtCollected` (CREDIT_ACCOUNT payments), always as separate columns (D87) |
| `GET /reports/voids?storeId&from&to&groupBy=reason\|employee\|item` | `REPORT_VIEW@store` | Includes wasted, after-dispatch and approver |
| `GET /reports/status?storeId` | `REPORT_VIEW@store` | Pending facts, last projected, last rebuild |
| `POST /reports/exports {report, params}` → `text/csv` | `REPORT_EXPORT@store` | Labels via `EmployeeDirectory`, `OrganizationApi.findTerminal`. Audited `REPORT_EXPORTED`. |
| `POST /reports/rebuild {storeId, from, to}` → 202 `RebuildView` | `REPORT_REBUILD`, Idem | One run at a time per store (409 `REBUILD_RUNNING`). Asynchronous, with progress. |

**Tests:**
- `SalesProjectionTest`: matches the documents; a redelivered event → one row.
- `ReportByBusinessDayTest`: across midnight and DST, with `local_hour`.
- `ReceiptsReportTest`: equals the cash movements; credit separate.
- `RebuildEqualsLiveTest`
- `ReportFreshnessTest`: a held listener → `pendingFacts > 0`, `final = false`.
- `ReportingReadOnlyRuleTest` (the S19 rule over the real module).
- Refund and credit projections are tested with `SettlementFacts` stubbed in the module test. The end-to-end proof is in S33.

**Acceptance:** A28 (except the refund and credit rows, which S33 proves end to end).

---

## Group 4 (after S29 and S21): S30, then S31 (S31 nets refunds, so it follows S30); S32 may still be running

### S30 — Settlement: refunds, in-doubt hardening, forced close
**Write scope:** `settlement/` (except `reconciliation/`), `db/migration/settlement/V9030–V9039`.

**Migration `V9030__settlement_create_refund.sql`:** `refund(id, payment_id FK, store_id, order_id, partition_id, credit_account_id, method_code, amount > 0, currency, reason NOT NULL, external_reference, session_id, operator_employee_id, approver_employee_id NOT NULL, confirmed_at)`. CHECK approver ≠ operator. Deferred Σ trigger. Append-only triggers. Index `(payment_id)`.

**SPI changes (D81):**
- `ProviderOutcome.NotFound`
- `PaymentProvider.acceptanceWindow()` (a default method, PT15M)

**Endpoints:**

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `POST /api/v1/pos/payments/{id}/refunds {amount, reason, externalReference?}` + Approver | `PAYMENT_REFUND`; approver `PAYMENT_REFUND_APPROVE` | yes | D77: 422 `REFUND_EXCEEDS_REFUNDABLE`, `CORRECTION_REQUIRES_FISCAL_ADAPTER`, `NO_OPEN_CASH_SESSION`, `SELF_APPROVAL`, `EXTERNAL_REFERENCE_REQUIRED` (non-cash) |
| `POST /api/v1/pos/cash/sessions/{id}/force-close {version, counts[], reason}` | `CASH_SESSION_FORCE_CLOSE` | yes | P22: any OPEN session of the POS store. 409 `PAYMENT_IN_DOUBT`. `CashApi.closeSessionForced`. Audited. |
| `POST /payment-attempts/{id}/resolve {outcome:"FAILED", …}` | unchanged | unchanged | 422 `RECLASSIFY_TOO_EARLY` (D81 (3)) |

**Background:**
- `InDoubtResolver` (scheduled, backoff per attempt via `next_poll_at` and `poll_count`, added in V9031). It runs the existing D63 resolve steps with no transaction, as SYSTEM.
- `SettlementFacts.refundFact` is implemented, and `RefundRecorded` is published.

**Tests:** `RefundTest` (cash, external, debt payment, overpayment after void), `RefundRaceTest`, `InDoubtPollingTest`, `ReclassifyAgeGuardTest`, `ForcedSessionCloseTest`, `IntegratedPaymentTest` (still green).

**Acceptance:** A23, the polling half of A25, the forced-close half of A26.

### S31 — Settlement: reconciliation (after S30)
**Write scope:** `settlement/reconciliation/**` (its own controller, service, repository, model and `ReconciliationRule` enum), `db/migration/settlement/V9040–V9049`. It **reads** payments, refunds, attempts and charges through settlement repositories. It writes none of them, and it does not edit `SettlementRule`.

**SPI:** `settlement.reconciliation.StatementSource`, an optional capability that a `PaymentProvider` adapter may also implement: `List<StatementLine> statement(String methodCode, LocalDate from, LocalDate to)`. Every implementation's first line is `ExternalCalls.requireNoTransaction()`. Only a test fake implements it in R3.

**Migration `V9040__settlement_create_reconciliation.sql`:**
- `reconciliation_batch(id, store_id, method_code, period_from, period_to, file_sha256, source CHECK IN ('CSV','PROVIDER'), line_count, imported_by_account_id, imported_at)`, with `UNIQUE(method_code, file_sha256)`
- `reconciliation_entry(id, batch_id FK, line_no, store_id, kind CHECK (D78), payment_id, provider_reference, authorization_code, occurred_at, internal_amount, provider_amount, fee_amount, difference, created_at)`, with `UNIQUE(batch_id, line_no)`, partial `UNIQUE(payment_id) WHERE kind IN ('MATCHED','AMOUNT_MISMATCH')`, and index `(store_id, kind)`
- `reconciliation_resolution(id, entry_id FK UNIQUE, action CHECK IN ('ACCEPTED','REFUNDED_AT_PROVIDER','ESCALATED','AUTO_MATCHED'), reason, actor_kind, actor_id, at)`
- All append-only

**Endpoints:**

| method & path | permission | idem | notes |
|---|---|---|---|
| `POST /api/v1/backoffice/reconciliation/batches` (multipart: `file`, `storeId`, `methodCode`, `periodFrom`, `periodTo`) → 201 `BatchView` | `RECONCILIATION_MANAGE@store` | yes | Canonical CSV (D78). 422 `STATEMENT_INVALID` {errors[line, field]}; 409 `STATEMENT_ALREADY_IMPORTED` {batchId}. |
| `POST /reconciliation/batches/fetch {storeId, methodCode, from, to}` | `RECONCILIATION_MANAGE@store` | `steps` | Only for a provider with `StatementSource` (422 `NO_STATEMENT_SOURCE`). The provider call runs with no tx. |
| `GET /reconciliation/batches?storeId&page`, `GET /batches/{id}/entries?kind&open&page` | `RECONCILIATION_VIEW@store` | — | |
| `POST /reconciliation/entries/{id}/resolutions {action, reason}` | `RECONCILIATION_MANAGE@store` | yes | 409 `ENTRY_RESOLVED`. Audited. |
| `GET /reconciliation/in-doubt?storeId` | `RECONCILIATION_VIEW@store` | — | UNKNOWN or PENDING attempts with age, poll count, last answer |
| `GET /reconciliation/documents?storeId&businessDate` | `RECONCILIATION_VIEW@store` | — | D80 exceptions |

**Tests:** `StatementImportTest` (every kind, re-import, cross-batch duplicate, unique-candidate time-window match, two equal candidates → not matched), `StatementFetchTest` (a fake `StatementSource`, no tx during the call), `DocumentsControlTest`, `ReconciliationNeverWritesMoneyTest` (row counts of `payment`, `refund` and `cash_movement` are unchanged by an import and its resolutions).

**Acceptance:** the reconciliation half of A25.

---

## S33 — Integrate (alone, after every slice has merged)

**Write scope:**
- `src/test/.../e2e`
- `docs/architecture/modules/` (`-Dmodulith.docs=true`)
- `CONTEXT.md`, `docs/architecture/backend-conventions.md`
- `docs/adr/0019…0024` and the ADR README (plus the ADR 0018 status per P39)
- `deploy/` (`.env.example` for the new properties)

**Deliverables:**
- **End-to-end journey** over HTTP with real security and `TcpFakePrinter`:
  1. R2 setup, plus a customer with a limit.
  2. A table with two rounds; split by items (2 diners) plus equal parts of the rest.
  3. Diner A pays by card and gets their partition document; diner B is put on account; a new dessert round; the rest is paid in cash.
  4. Close (residual document); refund an overpayment after a void within headroom.
  5. Debt payment the next day.
  6. Import a card statement (match plus one mismatch, resolved).
  7. Close the session and day, approve, close financially, reopen financially, adjust, approve round 1.
  8. Reports equal the documents, receipts and credit; audit query and export; a support grant.
- **`RestartMatrixTest`** (D90 a–f).
- **Report benchmark** (D86): seed one store-year and measure p95. A miss triggers a rollup follow-up slice, not a silent pass.
- **A32** (verify and rules); `deploy/test-backup-restore.sh` and `test-pitr.sh` re-run against the R3 schema.

---

## Blocked-by graph and parallel groups

```
S19 Platform prefactor (alone)
 ├─► S20 Customers ───────────────────────────────┐
 ├─► S21 Cash R3 ──────────────────────────────────┼────────────────┐
 ├─► S22 Fiscal R3 ──┐                             │                │
 ├─► S23 Orders R3 ──┴─► S28 Settlement split ──┬─► S29 Credit ──► S30 Refunds+hardening ──► S31 Reconciliation ─┐
 ├─► S24 Production R3                           │   (needs S20, S24)                                                │
 ├─► S25 Support access                          └─► S32 Reporting (needs S21, S22, S23) ───┼─► S33 Integrate
 ├─► S26 Audit query                                                                         │
 └─► S27 Deploy PITR ─────────────────────────────────────────────────────────────────────────┘
 (S30 also needs S21; S32 also needs S21; every group-1 slice must merge before S33)
```

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S19 alone | It touches `access/`, `shared/`, `config/`, `audit/` and call sites in organization, dining, catalog, fiscal, orders and identity |
| 1 | S20 ∥ S21 ∥ S22 ∥ S23 ∥ S24 ∥ S25 ∥ S26 ∥ S27 | `customers/`+11xxx, `cash/`+8007+, `fiscal/`+10007+, `orders/`+6008+, `production/`+7006+, `identity/`+3011+, `audit/`+1103+, `deploy/` |
| 2 | S28 (while S20, S21, S24–S27 may still run) | `settlement/`+9005–9019 |
| 3 | S29 ∥ S32 | `settlement/` (no `reconciliation/`)+9020–9029 plus the single production slip-listener file; `reporting/`+14xxx |
| 4 | S30 (∥ S32 if still running) | `settlement/` (no `reconciliation/`)+9030–9039 |
| 5 | S31 (∥ S32 if still running) | `settlement/reconciliation/**`+9040–9049, with its own rule enum |
| 6 | S33 alone | tests, docs, `deploy/` |

Rules, as in R1 and R2:
- No `pom.xml` edits.
- Each slice has its own `rtk mvn test -Dtest=…` that can go red on its own.
- Slices code against the contracts above and never read another module's tables.
- Within a group, merge in ascending slice number, so Flyway versions apply in order on persisted dev databases.
- No slice is interactive.
- Each PR targets `main`.

## Acceptance criteria → slices

| criterion (r3-decisions §16) | slices |
|---|---|
| A16, A17, A18, A19 | S22, S28 |
| A20, A21, A22 | S20, S22, S29 |
| A23 | S21, S30 |
| A24 | S23, S28 |
| A25 | S30, S31 |
| A26 | S21, S30 |
| A27 | S19, S26 |
| A28 | S32, S33 |
| A29 | S19, S29, S33 |
| A30 | S19, S24 |
| A31 | S20 |
| A32 | S19, S33 |
| A33 | S27, S33 |
| A34 | S25 |
