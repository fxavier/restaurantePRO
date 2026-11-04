# PRD R2 — Stage 2 "Operação integrada" (backend)

Status: **ACCEPTED** 2026-11-04 together with [r2-decisions.md](../design/r2-decisions.md) (cited as Dn and Pn). R1 decisions: [backend-decisions.md](../design/backend-decisions.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md). Conventions: [backend-conventions.md](../architecture/backend-conventions.md).

Base package `com.xavier.backend`, per-module layout D12, Flyway blocks D61. Every slice: TDD, no `@Disabled`, full `rtk mvn test` green at exit.
- Slices **do not commit `docs/architecture/modules/`** (D44). Only S17 does.
- No slice edits `pom.xml`: R2 needs no new dependency (D46, D57, D60). If one turns out to be needed, it goes into S7 or a new prefactor slice that runs alone.
- The names below are binding. Internals are the implementer's call.

Conventions for every endpoint below:
- **POS:** every endpoint calls `requirePos(...)` (D39) and takes its store and terminal from the session. Body fields never carry them.
- **Idem:** `Idempotency-Key` is required, through the typed `IdempotencyService.execute` (D47).
- **Approver:** the `X-Approver-Code` and `X-Approver-Pin` headers, verified before the transaction (D40).
- **Versions:** a stale `version` or `orderVersion` gives 409. Business rules give 422 `ProblemDetail` responses, each with a stable `code` property, for example `ORDER_IN_SETTLEMENT`.

---

## S7 — Platform prefactor (runs alone, first)

**Write scope:** `shared/`, `access/`, `audit/`, `config/`, the Actor construction sites in `identity/` (`PosLoginService`, `LoginService`, `SessionView`), `organization/` (the `StoreDeactivated` event only), the R1 helper call sites in `organization/ identity/ dining/ catalog/`, the catalog publish controller (typed idempotency), `src/test/.../architecture`, `src/test/.../access`, migrations `shared/V1004`, `audit/V1102`, `identity/V3005`, `docs/architecture/backend-conventions.md`, `application.yaml`.

| contract | detail |
|---|---|
| `shared.idempotency.IdempotencyService` | `<T> ResponseEntity<T> execute(String scope, String key, String target, Object body, Class<T> type, Supplier<ResponseEntity<T>> action)`. Stores 2xx only (D47.3), stores and replays `Location` (migration `V1004__shared_idempotency_location_and_retention.sql`: `response_location VARCHAR(2048)`, index `(status, completed_at)`), retention job (D47.4). The untyped overload is removed and `catalog` publish is migrated. |
| `shared.ExternalCalls` | `static void requireNoTransaction()`: throws `IllegalStateException` when a transaction or connection is bound to the thread (D48). |
| `shared.Money` | Adds `Money divide(BigDecimal divisor)` (HALF_UP to minor units, the only other rounding point next to `times`; D59). |
| `shared.persistence` | `UniqueConstraints` and `OptimisticLocking` (D43). The four module copies are deleted. |
| `shared.notify` | `ChangeNotifier.notifyAfterCommit(UUID storeId, ChangeTopic topic, UUID id)`; `enum ChangeTopic {TABLES, ORDER, CATALOG, PRINT_JOBS, PAYMENT_ATTEMPT}`. In-memory `SseEmitter` registry per store (D60). |
| `access.Actor` | `Actor(ActorKind kind, UUID userId, UUID employeeId, UUID terminalId, UUID storeId, Set<String> authorities)`; `enum ActorKind {ACCOUNT, EMPLOYEE}` (D38). |
| `access.AccessPolicy` | Adds `PosContext requirePos(Permission p)`, where `record PosContext(UUID employeeId, UUID terminalId, UUID storeId)`. Refuses a non-EMPLOYEE actor, a missing terminal, or a missing permission in the session store with 403. Refuses an inactive store through a pluggable `StoreActivity` SPI that organization implements. |
| `access.Permission` | Appends the D62 constants. `V3005__identity_r2_permissions.sql` grants them to the administrator role and to the default roles from the D62 table. |
| `audit.AuditLog` | Adds `recordAsCurrentActor(String action, String entityType, String entityId, UUID storeId, UUID approverId, String reason, Map<String,Object> details)` and `recordAsSystem(...)` (D41). `V1102__audit_actor_kind.sql` adds `actor_kind VARCHAR(10)`, nullable for R1 rows and `SYSTEM` for system writes. R1 call sites are migrated. |
| `organization.model.event.StoreDeactivated` | `record StoreDeactivated(UUID storeId, Instant at)`, published when a store goes active → inactive (D45). |
| `config` | `@EnableScheduling`; `NotificationController` `GET /api/v1/pos/notifications` (D60); `SecurityConfig` permits the `ASYNC` and `ERROR` dispatches; Hikari `maximum-pool-size: 20` (D40). |
| Tests | `ModularityTests` docs opt-in through `-Dmodulith.docs=true` (D44). `ExternalCallsTest`. Typed replay test (`Location` replayed, a 422 is not stored, retention deletes old records). `AccessPolicyTest` for `requirePos`. `SseNotificationTest` (an event after commit, none after a rollback). `RoleSeedTest` still green. |
| Conventions doc | D39–D44, D47, D48, and the store-scope rule D42. |

**Acceptance:** A15 (the arch half). The full R1 suite stays green after the changes.

---

## Group 1 (after S7, run in parallel): S8 ∥ S9 ∥ S10 ∥ S11 ∥ S12

### S8 — Dining occupation
**Write scope:** `dining/`, `db/migration/dining/V4003+`.
- Migrations:
  - `V4003__dining_create_table_occupation.sql`: `table_occupation(id, store_id, table_id FK, order_id, guests, opened_at, opened_by_employee_id, closed_at, close_reason, subtotal_printed_at)`, with partial unique `(table_id) WHERE closed_at IS NULL`, partial unique `(order_id) WHERE closed_at IS NULL`, and index `(store_id) WHERE closed_at IS NULL`.
  - `V4004__dining_table_lock.sql`: `restaurant_table.locked_at`, `locked_by_employee_id`, `lock_reason`.
- **`DiningApi`** (root). Every write method uses `MANDATORY` propagation:
  - `OccupationView openOccupation(UUID tableId, UUID orderId, int guests, UUID employeeId)`. Throws 409 `TABLE_OCCUPIED`, or 422 for `TABLE_INACTIVE`, `TABLE_LOCKED` and `STORE_MISMATCH`.
  - `void closeOccupation(UUID orderId, CloseReason reason)`
  - `OccupationView moveOccupation(UUID orderId, UUID toTableId)`
  - `void markSubtotalPrinted(UUID orderId)`
  - `TableState tableState(UUID tableId)`, returning store, active, area active, locked and the open occupation's order id.
- Endpoints:
  - `GET /api/v1/pos/tables`: `DINING_VIEW`, store from the session (D39). Gains `status FREE|OCCUPIED`, `locked`, `subtotalPrinted`, `openedAt`, `guests` and `openedByEmployeeId`, all from `statusOf`.
  - `POST /api/v1/pos/tables/{id}/lock {reason}`: `TABLE_LOCK`, Idem.
  - `POST /api/v1/pos/tables/{id}/unlock`: `TABLE_LOCK`, Idem.
- Rules:
  - Deactivating a table or area that has an open occupation → 422 `OCCUPIED`.
  - Every change notifies `TABLES` and is audited (D41).
- **Acceptance:** two concurrent `openOccupation` calls on one table → one wins and the other gets 409 (IT, two threads). An inactive or locked table is refused. Deactivation with an occupation open → 422.

### S9 — Catalog read API
**Write scope:** `catalog/` (no migration).
- **`CatalogApi`** (root):
  - `CatalogSnapshot currentSnapshot(UUID storeId)` (404 `NoPublishedCatalog`)
  - `CatalogSnapshot snapshot(UUID storeId, UUID releaseId)`
  - Each parses the jsonb once into the typed record.
- `CatalogSnapshot.TaxCategory.rateAt(Instant) → Optional<BigDecimal>`, the greatest `effectiveFrom ≤ instant` (D58).
- `CatalogSnapshot.Item.priceOf(UUID variantId) → Money`: the variant price, falling back to the item price.
- `GET /pos/catalog` takes its store from the session (D39).
- Publish notifies `CATALOG`.
- **Acceptance:** `rateAt` before, at and after a schedule change, and with no rate; a variant with a null price uses the item price; the snapshot round-trips through jsonb unchanged.

### S10 — Identity hardening
**Write scope:** `identity/`, `db/migration/identity/V3006+`. The role-shrink session fix is **out of scope** (R1 S6c).
1. A `StoreDeactivated` listener ends the POS sessions on that store's terminals (D45). It is idempotent.
2. The employee link grant check (r2-decisions §12).
3. `mustChangePin` after `reset-pin`: `POST /api/v1/auth/pos-pin` sets a new PIN, and only after that does the session get its authorities.
4. BackOffice login throttling (r2-decisions §12): table `identity.login_throttle(key, failures, window_start, blocked_until)`, response 429 `ProblemDetail`.
5. The Spring Session principal-name index includes the actor kind (D38).
- **Acceptance:** each item has an IT. The 11th bad password inside 15 minutes → 429. A deactivated store → its POS sessions are gone.

### S11 — Cash
**Write scope:** `cash/`, `db/migration/cash/V8xxx`. `package-info` follows D35.
- Migrations `V8001…`:
  - `cash_register`
  - `cash_day`: `UNIQUE(store_id, business_date)`
  - `cash_day_reopening`
  - `cash_session`: partial uniques per r2-decisions §4
  - `cash_session_count`
  - `cash_movement`: partial `UNIQUE(source_type, source_id)` and indexes `(store_id, cash_day_id)` and `(session_id)`
  - `cash_closing`: `UNIQUE(register_id, cash_day_id, attempt_no)`, plus the live-row partial unique
  - `cash_closing_line`
  - immutability triggers on `cash_movement` and `cash_session_count`
- **`CashApi`** (root). The write methods are `MANDATORY` and exist for settlement:
  - `Optional<OpenSessionView> openSessionOf(UUID storeId, UUID employeeId)`
  - `MovementView recordReceipt(ReceiptCommand cmd)`, where `ReceiptCommand(UUID sessionId, String methodCode, Money amount, UUID paymentId, UUID actorEmployeeId)`. It is idempotent: the same `paymentId` returns the existing movement.
  - `SessionView lockSession(UUID sessionId)` (`FOR UPDATE`)
  - `void closeSession(UUID sessionId, long expectedVersion, List<MethodCount> counts, UUID actorEmployeeId)`
  - `DayView lockDay(UUID storeId, LocalDate businessDate)`
  - `void closeDayOperationally(UUID dayId, long expectedVersion, UUID actorId, ActorKind kind)`
  - `List<UUID> openSessionIds(UUID dayId)`
- Endpoints owned by cash. Except for the reads, each is Idem:

| method & path | permission | notes |
|---|---|---|
| `GET/POST /api/v1/backoffice/cash/registers`, `PUT /registers/{id}` | `CASH_REGISTER_MANAGE@store` | `@Version` |
| `POST /api/v1/pos/cash/sessions {registerId, openingFloat}` | `CASH_OPERATE` | Creates the `CashDay` if absent. 422 `DAY_NOT_OPEN`, 409 when the register or operator already has an open session. Inserts an `OPENING_FLOAT` movement. Reads the day `FOR SHARE`. |
| `GET /api/v1/pos/cash/sessions/current`, `GET /sessions/{id}` | `CASH_OPERATE` / `CASH_VIEW` | Expected amount per method |
| `POST /api/v1/pos/cash/movements {sessionId, type, amount, reason}` | `CASH_OPERATE`; `EXPENSE` also needs `CASH_EXPENSE` | Types `CASH_IN`, `CASH_OUT`, `EXPENSE`; method `CASH`. Only in your own OPEN session. |
| `GET /api/v1/pos/cash/days/current` | `CASH_VIEW` | |
| `POST /api/v1/pos/cash/closings {registerId, cashDayId}` | `CASH_OPERATE` | DRAFT. 422 when a session is still open. |
| `PUT /api/v1/pos/cash/closings/{id} {version, justifications[]}`, `POST /closings/{id}/submit` | `CASH_OPERATE` | A justification is required when the difference ≠ 0 |
| `POST /api/v1/backoffice/cash/closings/{id}/approve` and `/reject {reason}` | `CASH_CLOSING_APPROVE@store` | Rejected → a new DRAFT is allowed (`attempt_no + 1`) |
| `POST /api/v1/backoffice/cash/days/{id}/financial-close` | `CASH_CLOSING_APPROVE@store` | Requires every closing of the day to be APPROVED |
| `POST /api/v1/backoffice/cash/days/{id}/reopen {reason}` + Approver | `CASH_DAY_CLOSE@store`; the approver needs `CASH_CLOSING_APPROVE` | Only from OPERATIONALLY_CLOSED (P19) |

- Metric: `cash_closing_difference_total`, per store. It has no order labels.
- **Acceptance:** A11 (the cash half). `recordReceipt` twice with one `paymentId` → one row. A second open session for the same register → 409. Updating a movement fails at DB level.

### S12 — Fiscal
**Write scope:** `fiscal/`, `db/migration/fiscal/V10xxx`.
- Migrations `V10001…`:
  - `fiscal_series` (uniques per r2-decisions §4)
  - `fiscal_document` (`UNIQUE(series_id, number)`, the source partial unique, CHECK ISSUED ⇒ number)
  - `fiscal_document_line`
  - `fiscal_document_tax`
  - `fiscal_document_print`: `UNIQUE(document_id, copy_no)`
  - immutability trigger for ISSUED documents and their lines
- **`FiscalApi`** (root):
  - `DocumentView createSaleDraft(SaleDraftCommand cmd)`: `MANDATORY`; idempotent per `(ORDER, orderId)`. `SaleDraftCommand(UUID storeId, UUID orderId, Buyer buyer, List<DraftLine> lines, Money totalGross, Money totalNet, Money totalTax)`.
  - `DocumentView issue(UUID documentId, UUID actorEmployeeId)`: `REQUIRES_NEW`; on failure it records `last_error` in a separate transaction and rethrows `FiscalIssueFailedException` (422 `FISCAL_PENDING`).
  - `Optional<DocumentView> saleDocumentFor(UUID orderId)`
  - `long countDrafts(UUID storeId)`
- **SPI** `FiscalAdapter` (D54). Implementation `fiscal.adapter.internal.InternalFiscalAdapter`. Startup guard `restaurantpro.fiscal.adapter` + `allow-non-certified`. `HealthIndicator` `fiscal`.
- Events (`fiscal.model.event`):
  - `FiscalDocumentIssued(UUID documentId, UUID storeId, UUID terminalId, DocumentPrintout printout)`
  - `FiscalDocumentReprintRequested(UUID documentId, int copyNo, UUID storeId, UUID terminalId, DocumentPrintout printout)`
  - `DocumentPrintout(List<String> lines, String qrPayload)`, where `qrPayload` is nullable
- Endpoints:

| method & path | permission | notes |
|---|---|---|
| `GET/POST /api/v1/backoffice/fiscal/series`, `POST /series/{id}/deactivate` | `FISCAL_MANAGE` | `firstNumber` at creation only. There is no endpoint that changes `next_number` (§9). |
| `GET /api/v1/backoffice/fiscal/documents?storeId&from&to&page` and `/documents/{id}` | `FISCAL_DOCUMENT_VIEW@store` | Index `(store_id, issued_at)` |
| `GET /api/v1/pos/documents/{id}` | `FISCAL_DOCUMENT_VIEW` | |
| `POST /api/v1/pos/documents/{id}/reprints` | `FISCAL_DOCUMENT_REPRINT`, Idem | Next `copy_no`, publishes a reprint event, audited. Creates no document, payment or movement (§8.4). |

- **Acceptance:** A9 (`GaplessNumberingTest`), A13, the reprint half of A10.

---

## S13 — Orders core (after S8, S9)
**Write scope:** `orders/`, `db/migration/orders/V6xxx`.
- Migrations `V6001…`:
  - `order_header` (§8.1): the partial unique `(table_id) WHERE lifecycle='OPEN'`, CHECKs per r2-decisions §4, index `(store_id, lifecycle)` and `(store_id, business_day)`, plus a sequence `order_number_seq` (display only, gaps are fine)
  - `order_line`: index `(order_id, status)` and the confirmed-immutability trigger
  - `order_batch`: `UNIQUE(order_id, batch_no)`
  - `order_line_void`
  - `order_transfer`
  - `order_transfer_line`

  S14 uses the last three, but they are created here so that net-quantity reads are final.
- **`OrdersApi`** (root, for settlement):
  - `OrderBillingView billingView(UUID orderId)`, returning `orderId, storeId, version, lifecycle, billingLock, businessDay, List<BillingLine>(lineId, description, netQty, unitPrice, gross, net, tax, taxCode, ratePercent), Money due`
  - `List<OrderBillingView> openBillingViews(UUID storeId)`
  - `void lockForPayment(UUID orderId)` and `void releasePaymentLock(UUID orderId)`
  - `void freezeForInvoice(UUID orderId)`
  - `void close(UUID orderId, long expectedVersion, UUID actorEmployeeId)`: closes the occupation through `DiningApi`
  - `long countOpen(UUID storeId, LocalDate businessDayInclusive)`

  Every write is `MANDATORY` and force-increments the version (D49).
- **`OrdersProductionApi`** (root, for production): `void markBatchDispatched(UUID batchId)`. It is idempotent.
- Events (`orders.model.event`, the contract for S15):
  - `OrderBatchConfirmed(UUID orderId, UUID batchId, int batchNo, UUID storeId, UUID terminalId, long orderNumber, String tableCode, UUID employeeId, Instant confirmedAt, List<Line> lines)`, where `Line(UUID lineId, String itemCode, String description, String variantName, BigDecimal quantity, String notes, String destination)`
  - `OrderLinesVoided(UUID orderId, UUID voidId, UUID storeId, UUID terminalId, long orderNumber, String tableCode, Line line, BigDecimal quantity, String reason, Instant at)`: defined here, published by S14
  - `SubtotalRequested(UUID orderId, UUID storeId, UUID terminalId, long orderNumber, String tableCode, List<SubtotalLine> lines, Money due)`: defined here, published by S14
- `orders.LineAmounts.of(Money unitPrice, BigDecimal netQty, BigDecimal ratePercent)` (D59).
- Endpoints (POS, store and terminal from the session):

| method & path | permission | idem | errors |
|---|---|---|---|
| `POST /api/v1/pos/tables/{id}/open {guests}` → 201 `OrderView` | `ORDER_EDIT` | yes | 409 `TABLE_OCCUPIED`; 422 `TABLE_INACTIVE`/`TABLE_LOCKED`/`STORE_INACTIVE` |
| `POST /api/v1/pos/orders {mode: COUNTER\|TAKEAWAY, guests?}` → 201 | `ORDER_EDIT` | yes | 422 for mode `DINE_IN` (use table open) |
| `GET /api/v1/pos/orders?lifecycle=OPEN`, `GET /orders/{id}` | `ORDER_VIEW` | — | 404; 403 for another store (D42) |
| `POST /api/v1/pos/orders/{id}/lines {itemId, variantId?, quantity, notes?}` → DRAFT with a preview price | `ORDER_EDIT` | yes | 422 `ITEM_NOT_IN_CATALOG`, `ORDER_FROZEN`, `TABLE_LOCKED` |
| `PUT/DELETE /api/v1/pos/orders/{id}/lines/{lineId} {version,…}` | `ORDER_EDIT` | — | 409 when the line is not DRAFT or the version is stale |
| `POST /api/v1/pos/orders/{id}/batches {orderVersion, catalogVersion, lineIds[]}` → 201 `BatchView` | `ORDER_EDIT` | yes | 409 stale `orderVersion` / `CATALOG_CHANGED` / line not DRAFT; 422 `NO_TAX_RATE_IN_EFFECT`, `ITEM_NOT_IN_CATALOG`, `ORDER_FROZEN` |

- Drafts do not bump `Order.version`. A confirmation does.
- Every change notifies `ORDER` (and `TABLES` when a table opens), and is audited.
- **Acceptance:** A1, A2, A3 (the line half), A4 (the publication half), and `LineAmounts` unit tests (rounding, zero rate, 3-decimal weight).

## Group 2 (after S13, S11, S12, run in parallel): S14 ∥ S15 ∥ S16

### S14 — Orders adjustments (after S13)
**Write scope:** `orders/` (new services, controllers and tests only). No new migration unless one is needed, which would be `V6010+`. All endpoints are POS and Idem:

| method & path | permission | notes |
|---|---|---|
| `POST /orders/{id}/voids {orderVersion, lineId, quantity, reason, wasted}` + Approver | `ORDER_VOID`; the approver needs `ORDER_VOID_APPROVE` | 422 `QUANTITY_EXCEEDS_NET`, `ORDER_IN_SETTLEMENT`, `SELF_APPROVAL`. Publishes `OrderLinesVoided`. |
| `POST /orders/{id}/transfers {orderVersion, targetOrderId, targetOrderVersion, lines[{lineId, quantity}], cancelSourceWhenEmpty}` | `ORDER_TRANSFER` | Same store. Copies the snapshot to the target with no batch. Both versions are bumped in ascending-id order. A merge sets `cancelSourceWhenEmpty=true`. |
| `POST /orders/{id}/table-move {orderVersion, tableId}` | `ORDER_TRANSFER` | `DiningApi.moveOccupation`; 409 when the target table is occupied |
| `POST /orders/{id}/cancel {orderVersion, reason}` | `ORDER_CANCEL` | Net billable quantity 0 and `billingLock=NONE`, else 422. Closes the occupation. |
| `POST /orders/{id}/subtotal/print` | `ORDER_EDIT` | Publishes `SubtotalRequested` and calls `DiningApi.markSubtotalPrinted`. Prints only; it is not a document (§8.1). |

**Acceptance:** A6. A transfer leaves Σ net quantities across both orders unchanged and creates no batch. A void while `billingLock=PAYMENT` → 422. Concurrent transfers A→B and B→A do not deadlock (IT).

### S15 — Production (after S13, S12)
**Write scope:** `production/`, `db/migration/production/V7xxx`.
- Migrations `V7001…`:
  - `printer`: `UNIQUE(store_id, code)`
  - `destination_route`: PK `(store_id, destination_code)`
  - `terminal_printer`
  - `production_ticket`: `UNIQUE(source_type, source_id, destination)`
  - `print_job`: `UNIQUE(dedupe_key)`, partial index `(next_attempt_at) WHERE status IN ('QUEUED','RETRY_WAIT')`, index `(printer_id, status)` and `(store_id, status, created_at)`
- Listeners (`@ApplicationModuleListener`, idempotent through the ticket unique key):
  - `OrderBatchConfirmed` → a ticket per destination, jobs, then `OrdersProductionApi.markBatchDispatched`
  - `OrderLinesVoided` → a VOID ticket
  - `SubtotalRequested` → a SUBTOTAL ticket on the terminal printer
  - `FiscalDocumentIssued` and `FiscalDocumentReprintRequested` → a DOCUMENT ticket, kind ORIGINAL or COPY, on the terminal printer
- The worker follows D50 §7.2. Ports: `PrinterTransport` with `EscPosTcpTransport`, and `EscPosRenderer` (D57). Every outcome notifies `PRINT_JOBS`.
- Metrics: `print_jobs{status}` gauge and `production_unrouted_total`.
- Endpoints:

| method & path | permission | notes |
|---|---|---|
| `GET/POST /api/v1/backoffice/printers`, `PUT /printers/{id}` | `PRODUCTION_MANAGE@store` | host, port, code page, active |
| `PUT /api/v1/backoffice/printers/{id}/mode {mode, redirectPrinterId?, version}` | `PRODUCTION_MANAGE`; `IGNORE` also needs `PRODUCTION_SUPPRESS` | Audited. Moves pending jobs on REDIRECT (D56). |
| `POST /api/v1/backoffice/printers/{id}/test` | `PRODUCTION_MANAGE`, Idem | A TEST ticket and job |
| `GET/PUT /api/v1/backoffice/production-routes`, `/terminal-printers` | `PRODUCTION_MANAGE@store` | |
| `GET /api/v1/backoffice/print-jobs?storeId&status&from` and `GET /api/v1/pos/print-jobs?status` | `PRODUCTION_VIEW` | |
| `POST /api/v1/{pos\|backoffice}/print-jobs/{id}/retry` | `PRODUCTION_OPERATE`, Idem | Only from FAILED or UNKNOWN. Creates a new job (`retry_of`). |
| `POST /api/v1/{pos\|backoffice}/print-jobs/{id}/acknowledge` | `PRODUCTION_OPERATE`, Idem | UNKNOWN → resolution `CONFIRMED_PRINTED` |
| `POST /api/v1/pos/production-tickets/{id}/reprints` | `PRODUCTION_OPERATE`, Idem | A COPY job, audited |

**Acceptance:** A4 (the production half), A5, the reprint half of A10 (DOCUMENT COPY). IT `PrintQueueTest` with `TcpFakePrinter`. An IGNORE printer → the ticket is `suppressed`, there is no job, and the audit row exists.

### S16 — Settlement (after S13, S11, S12, S16a)
**Prefactor S16a (D63), merged before S16:** `shared/idempotency/` + `db/migration/shared/V1005__shared_idempotency_step_lease.sql`. It adds `IdempotencyService.executeSteps(scope, key, target, body, Class<T>, Supplier<ResponseEntity<T>> steps)` (claim with lease → steps with no tx → complete by owner) and the lease columns, and leaves `execute` unchanged. Test: `rtk mvn test -Dtest=IdempotencyStepsTest`.

**Write scope:** `settlement/`, `db/migration/settlement/V9xxx`.
- Migrations `V9001…`:
  - `payment_method`: `CASH` is seeded; `UNIQUE(code)`
  - `order_settlement`: PK `order_id`
  - `payment_attempt`: index `(order_id)`, partial index `(status, updated_at) WHERE status IN ('CREATED','PENDING','UNKNOWN')`, index `(session_id, status)`, `UNIQUE(provider_code, provider_reference)`
  - `payment`: `UNIQUE(attempt_id)`, index `(order_id)`
  - `payment_allocation`
  - immutability triggers on `payment` and `payment_allocation`
- **SPI** `PaymentProvider` (D53). `PaymentSubmitter`, a bounded executor plus the sweeper (D48). `FakePaymentProvider` lives in test sources.
- Gauge `payment_attempts_in_doubt`.
- Endpoints (POS unless stated otherwise):

| method & path | permission | idem | notes / errors |
|---|---|---|---|
| `GET/POST /api/v1/backoffice/payment-methods`, `PUT /{id}` | `PAYMENT_METHOD_MANAGE` | — | 422 when an `INTEGRATED` method has no provider bean |
| `POST /api/v1/pos/payment-attempts {orderId, methodCode, amount, tendered?}` | `PAYMENT_COLLECT` | yes | CASH → 201 SUCCEEDED (Payment + movement in one transaction); EXTERNAL_TERMINAL → 201 PENDING; INTEGRATED → 202 CREATED + `Location`. 422 `NO_OPEN_CASH_SESSION`, `AMOUNT_EXCEEDS_BALANCE`, `ORDER_NOT_OPEN`; 409 `PAYMENT_IN_DOUBT`. The first attempt calls `lockForPayment`. |
| `GET /api/v1/pos/payment-attempts/{id}` | `PAYMENT_COLLECT` | — | The source of truth for the outcome |
| `POST /payment-attempts/{id}/confirm {authorizationCode?}` | `PAYMENT_COLLECT` | yes | EXTERNAL_TERMINAL PENDING → SUCCEEDED (D50 §7.5). No card data is stored or logged. |
| `POST /payment-attempts/{id}/cancel {reason}` | `PAYMENT_COLLECT` | yes | CREATED or PENDING (not INTEGRATED once PENDING) → CANCELLED. Calls `releasePaymentLock` when nothing else is open or paid. |
| `POST /payment-attempts/{id}/resolve {outcome?, reason?}` (+ Approver when `outcome` is given) | `PAYMENT_RESOLVE`; the approver needs `PAYMENT_RESOLVE` | yes: `executeSteps` without `outcome`, `execute` with it (D63) | With no body it queries the provider. With `outcome` it is a manual reclassification (D50 §7.4). |
| `GET /api/v1/pos/orders/{id}/settlement` | `ORDER_VIEW` | — | `due, paid, balance, financialState, fiscalState, openAttempts` |
| `GET /api/v1/pos/settlement/open-orders` | `ORDER_VIEW` | — | The per-table consumption and balance for the map |
| `POST /api/v1/pos/orders/{id}/documents {buyer?}` | `PAYMENT_COLLECT` | yes, `executeSteps` (D63) | s1 tx `freezeForInvoice` + draft, s2 `FiscalApi.issue` with no tx (D55) → 201 `DocumentView` ISSUED. 422 `FISCAL_PENDING` {documentId} on adapter failure (not stored; a retry with the same key issues again). |
| `POST /api/v1/pos/orders/{id}/close {orderVersion}` | `PAYMENT_COLLECT` | yes, `executeSteps` (D63) | D55 → 200 `OrderSettlementView` CLOSED. An order that is already CLOSED returns its view (idempotent by state). 422 `NOT_SETTLED`, `FISCAL_PENDING`; 409 `PAYMENT_IN_DOUBT` / stale version, or the same key still in progress (live lease) |
| `POST /api/v1/pos/cash/sessions/{id}/close {version, counts[{methodCode, amount}]}` | `CASH_OPERATE` | yes | 409 when an attempt on the session is in doubt (§8.4) |
| `POST /api/v1/pos/cash/days/{id}/operational-close {version}` | `CASH_DAY_CLOSE` | yes | 422 `OPEN_ORDERS`, `OPEN_SESSIONS`, `PAYMENT_IN_DOUBT`, `FISCAL_PENDING` |
| `GET /api/v1/backoffice/settlement/pending?storeId` | `PAYMENT_RESOLVE@store` | — | In-doubt attempts, fiscal drafts, settled-but-open orders |

**Acceptance:** A7, A8, A10 (no cash on issue), A11 (the coordination half), A12 (the payment and closing paths). IT `DoublePaymentRaceTest`, `IntegratedPaymentTest`, `OrderCloseTest`, `OrderCloseResumeTest` (D63: a failure injected after each of s1, s2 and s3, then a retry with the same key → one document number, one close, the final-state response; a fiscal failure → 422, then a retry → ISSUED with no gap), `SessionCloseBlockedByUnknownTest`, `DayCloseBlockedByOpenOrderTest`.

---

## S17 — Integrate (on the R2 branch, after everything has merged)

**Write scope:** `src/test/.../e2e`, `docs/architecture/modules/` (regenerated with `-Dmodulith.docs=true`), `CONTEXT.md`, `docs/architecture/backend-conventions.md`, `docs/adr/0001…` (the ★ decisions), `deploy/` (the `.env.example` fiscal flags).

It contains one end-to-end `@SpringBootTest` over HTTP with real security and `TcpFakePrinter`:
1. R1 setup, then a printer, a route, a register, a series and payment methods.
2. POS login, open a session, open a table, add drafts, confirm round 1 (the kitchen ticket is printed), confirm round 2 (only the new lines are printed).
3. Void with an approver, subtotal print.
4. Pay part in cash with change, pay the rest by external card with confirm, issue the document (printed), close the order (the table is FREE).
5. Close the session with counts, create, submit and approve the closing, close the day operationally, then financially.

S17 also runs A14 (SSE) and A15 (verify), plus a smoke latency check of p95 on local commands with 10 concurrent clients. That check is informational (§12) and is not a gate. It re-runs `deploy/test-backup-restore.sh` against the R2 schema.

---

## Blocked-by graph and parallel groups

```
S7 Platform prefactor (alone)
 ├─► S8 Dining occupation ─┐
 ├─► S9 Catalog read API ──┴─► S13 Orders core ─┬─► S14 Orders adjustments ─┐
 ├─► S10 Identity hardening ───────────────────────────────────────────────┤
 ├─► S11 Cash ──────────────────────────────────┼─► S16 Settlement ─────────┼─► S17 Integrate
 └─► S12 Fiscal ────────────────────────────────┴─► S15 Production ────────┘
     (S16 also needs S11 and S12; S15 also needs S12)
```

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S7 alone | It touches `shared/`, `access/`, `audit/`, `config/` and the call sites in every R1 module. |
| 1 | S8 ∥ S9 ∥ S10 ∥ S11 ∥ S12 | `dining/`+4xxx, `catalog/`, `identity/`+3xxx, `cash/`+8xxx, `fiscal/`+10xxx |
| 2 | S13 (while S10, S11 and S12 may still be running) | `orders/`+6xxx |
| 3 | S14 ∥ S15 ∥ S16 | `orders/` (new files only), `production/`+7xxx, `settlement/`+9xxx |
| 4 | S17 alone | tests, docs, `deploy/` |

Rules, as in R1: no `pom.xml` edits; each slice has its own `rtk mvn test -Dtest=…` that can go red on its own; merges happen one at a time, with the full suite after each merge; no slice is interactive.

S15 and S16 code against the S13 and S12 public contracts above. They never read another module's tables.

## Acceptance criteria → slices

| criterion (r2-decisions §13) | slices |
|---|---|
| A1 | S8, S13 |
| A2, A3 | S13 |
| A4 | S13, S15 |
| A5 | S15 |
| A6 | S14, S15 |
| A7, A8 | S16 |
| A9 | S12 |
| A10 | S12, S15, S16 |
| A11 | S11, S16 |
| A12 | S7, S11, S12, S16 |
| A13 | S12 |
| A14 | S7, S17 |
| A15 | S7, S17 |
