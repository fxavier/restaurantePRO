# restaurantPRO backend — R3 decisions for approval (spec Stage 3)

Status: **APPROVED** 2026-11-05 by Xavier Francisco ("aprovado": every recommendation, P22–P39). This document continues [backend-decisions.md](backend-decisions.md) (D1–D33, P1–P6, B1–B3) and [r2-decisions.md](r2-decisions.md) (D34–D63, P7–P21, B4–B5). Those decisions stay in force, and the ids here continue from **D64, P22 and B6**. Answer by id, for example "P24: keep it as built" or "D70: invoice only at close". Once approved, the decisions marked ★ become ADRs `docs/adr/0019…` in S33.

**Amended 2026-11-06** after the build (S19–S32 merged), from three decisions of Xavier Francisco: **D96** (the tax cent a settled void leaves), **D97** (the payment breakdown on a document) and **D98** (production backup encryption), in [§18](#18-amendments-after-the-build-d96d98). They supersede the parts of the D68 amendment, D74, D80 and D90 marked there, and add slices S34–S36 to the PRD.

Source of truth: [product_description.md](../product_description.md), cited as §n. The slices, contracts and graph are in [PRD R3](../prd/r3-stage3.md). The glossary is in [CONTEXT.md](../../CONTEXT.md), and the coding rules are in [backend-conventions.md](../architecture/backend-conventions.md). The R2 code is the baseline: branch `feat/backend-stage2`, 1038 tests green.

---

## 0. Blockers and decisions that need you

**Blockers to *building* R3: none.** Launch conditions B4 (a certified fiscal adapter for the chosen market) and B5 (tests with the real printers) still stand. R3 adds none of its own. Every market-dependent point sits behind a port with a buildable default: the fiscal adapter (D54), the payment provider (D53, D81) and the statement source (D78).

**No spec change is proposed.** Where the spec is silent, the simplest consistent rule is chosen and marked as an assumption in the decision that needs it.

**Questions only you can answer.** Each row has my recommendation. I build R3 with it unless you override it.

| id | question | recommendation (default) | what changes if you choose otherwise |
|---|---|---|---|
| P22 | Can a manager close a cash session that its operator abandoned (for example, a shift ended without a close)? | **Yes.** Add `CASH_SESSION_FORCE_CLOSE` (Gerente). The manager closes it on a POS of the store, counts the drawer, and gives a reason. The session records the closer and `close_kind = FORCED`, and the close is audited. A payment in doubt still blocks the close (§8.4). | **No:** the day can never close while a session is abandoned (R2 behaviour). |
| P23 | Blind count: may the operator see the expected amount before counting? | **No (as built).** The count is recorded without the expected amount and cannot be changed. The difference shows only after the count is stored, to whoever prepares the closing, who must justify it. There is no recount. A later fix is a manager adjustment (D79). | Showing the expected amount first defeats the control. |
| P24 | Do expenses need a second person (an approver)? | **Yes.** The cashier records an expense in their own session with `CASH_OPERATE`. An approver who holds `CASH_EXPENSE` authorizes it with code and PIN (D40), the same way voids work. This changes R2, where `CASH_EXPENSE` on the operator alone was enough. | Keep R2: only managers can record expenses, and nobody else checks them. |
| P25 | Can a reopened business day take new sales? | **No: corrections only.** A reopened day accepts adjustments, session closes, and closing resubmission and approval. It accepts no new sessions and no new orders. (Orders always take the current business day from the clock.) | New sessions in a reopened day: its payments would mix with the following day's orders in the reports. |
| P26 | Does a void before the round is dispatched need an approver? | **Yes, as built.** Every void of a CONFIRMED line needs an approver. Lines stay freely editable while they are DRAFT. Dispatch happens within milliseconds, so the window without an approver would be meaningless. | No approver before dispatch: the rule would depend on worker timing. |
| P27 | Can a subtotal be printed after the document is issued? | **No.** Return 422 `DOCUMENT_ISSUED`. The POS reprints the document as a copy instead (D50, §8.4). | Allowing it puts a second, non-fiscal bill next to the fiscal one. |
| P28 | When a new business day starts while an older day is still open: warn or block? | **Warn and allow.** After the cutoff, a session opens in the new day and the response carries `warnings: [PREVIOUS_DAY_OPEN]`. The older day then accepts no new sessions and can only be closed. This replaces R2's "current day = oldest open day", so a payment's cash day matches its order's business day (D85). | **Block:** the morning shift cannot trade until a manager closes yesterday. **Keep R2:** sales made after the cutoff land in yesterday's cash day. |
| P29 | What authorizes a credit sale ("venda a crédito", §10 "autorização adicional para… crédito")? | **The `CREDIT_SALE` permission (Gerente, Caixa), only within the available limit.** There is no over-limit override in R3. Who may change a limit is controlled separately (`CREDIT_LIMIT_MANAGE`). | An approver for every credit sale: safer, but slower at the till. |
| P30 | Refund ("devolução", §8.3) policy. | **An approver is required (`PAYMENT_REFUND_APPROVE`, Gerente).** The refund goes to the original method only. You can refund money that no issued document covers, or an overpayment. Refunding an invoiced sale needs a corrective document, which comes with the market adapter (P31). | Refunds without an approver: money leaves with a single person's authorization. |
| P31 | Corrective documents (credit notes, cancellations) in R3? | **No: the market run.** Their semantics depend on the regime (P8, §5). R3 refuses a void or refund of invoiced quantities with 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`. | A generic credit note now would have to be reworked once the regime is known. |
| P32 | Can a line be voided after part of the bill has been paid? This lifts part of P13. | **Yes, within headroom.** It is allowed when the void leaves the bill at or above what has been paid or put on account, and the quantity is neither in a live partition nor invoiced (D71). Otherwise, refund first. | Keep P13: no void once any payment exists. |
| P33 | Customer debt: can a customer pay in advance (keep a credit balance)? Which document does a debt payment issue? | **No advance:** a payment is at most the open debt. A debt payment prints a **non-fiscal** payment slip. A fiscal receipt document, if the regime requires one, comes with the market adapter. | Advances need a "customer credit balance" liability and its own documents. |
| P34 | Bolsas (waiter floats), envelopes and `CashShift` (§4, §8.6), deferred from R2 (P16). | **Defer them until after R3**, unless the pilot store uses waiter floats. | Including them adds a second responsibility model to cash and to every report (one more slice, roughly). |
| P35 | Menus, daily dishes, macros, automatic items and quick pages (P20 asked you to choose R3 or R4). | **R4**, with promotions. They share pricing rules. | R3 grows by a catalog slice. |
| P36 | Support access (§5 "temporário, autorizado e auditado"; D29 deferred it to R3). | **Yes, in R3.** An administrator grants it for at most 24 h, with a reason. It expires by itself and is audited. MFA stays deferred (D26), on the assumption that support connects over a VPN to the LAN. | Defer: support uses a shared admin account, which cannot be audited per person. |
| P37 | Which business day does a sale belong to, and which export format? | **The order's business day** (fixed when the order opens) for sales, and the cash day for money (D85). Exports are **CSV**. Provider statements are imported in one canonical CSV format (D78). | Attributing sales by the instant of issue: an order opened before the cutoff and closed after it would split across two days. |
| P38 | Customers and credit across stores. | **Company-wide.** One customer and one credit account for every store. A manager of any store sees the customer's full statement. | Per-store accounts: a customer would have separate limits and debts in each store. |
| P39 | Ratify D63 / ADR 0018 (`executeSteps`). It is still Proposed. R3 uses it for partition documents, credit sales and provider statement fetches. | **Ratify it as built.** | Rejecting it reopens how `/documents`, `/close` and `/resolve` take an Idempotency-Key. |

---

## 1. Scope (§13 Stage 3: "Split/parcial, crédito, reconciliação, auditoria, relatórios e recuperação de falhas")

| area | IN R3 | deferred (where) |
|---|---|---|
| Split and partial payment (§4 "divisão por artigos/quantidades ou partes", §8.2 BillPartition) | `BillPartition` by items/quantities and by amount, and equal parts. Payments to a partition. A separate document per item partition. The residual document at close. A coordinated void within headroom (P32). Open orders carry `tableId`. | Splitting with fractional quantities per diner on documents (the market adapter decides) |
| Credit (§5 "Clientes e crédito", §8.4 "reserva transacional do limite") | The `customers` module: customers, contacts, addresses, search by suffix and by contains, `CustomerCreditAccount`, limit changes, block, `CreditReservation`, `CreditLedgerEntry`, statement, aging. Credit sale, and debt payment received later. | Loyalty (promotions, R4). Advances (P33). Customer merge. |
| Refunds (§8.3 Refund) | `Refund` facts for cash and external-terminal methods. Refunds of debt payments. | Integrated-provider refund API (no provider yet, P10). Corrective documents (P31). |
| Reconciliation (§5, §8.1 ReconciliationEntry) | Provider statements against payments (CSV import plus a port). Cash adjustments after a session closes, a stale-closing guard, financial reopen, forced close (P22). A documents-against-payments control. Hardening of payments in doubt. | Accounting entries for fees ("não inclui contabilidade geral") |
| Audit (§10) | Query API, entity history, audited CSV export, nested secret guard. Support access (P36). | Tamper-evident hash chain (not required by §10, alternative in D82) |
| Reports (§5, §8.6) | The `reporting` module: `SalesRecord` and its lines, payments, refunds, credit charges, void projections. Sales by day, hour, store, area, terminal, employee, item, family and tax rate. Top items, voids, receipts by method, credit apart from cash. Rebuild, freshness, CSV export. Cash, credit and reconciliation reports come from their owning modules (D87). | Stock, margins, waste and purchases (inventory, R4). Discounts and offers (promotions, R4). |
| Failure recovery (§7, §11, §14 #3, #5) | Runtime resubmission of event publications, plus failed-publication tooling. Unrouted production work becomes visible and routable. Subtotal request id. Automatic provider status polling. A credit-charge recovery sweeper. A crash-point test matrix. SSE stops promptly on shutdown. WAL/PITR (the RPO target in §11). | High availability (§11 "backups não são alta disponibilidade") |

**D64 — R3 creates two modules, `customers` and `reporting`** (D1: a module is created only when its stage starts). `promotions` and `inventory` are not created. `BillPartition`, `CreditCharge`, `Refund` and `ReconciliationEntry` live in `settlement`, as §8.1 places them. No "finance" module is created (§8.1).

---

## 2. Modules and dependencies

**D65 ★ — Allowed dependencies for R3.** This is D35 with these amendments (⊕ added, ⊖ removed from the D13 plan). Every D35 forbidden edge stays.

```
customers  → shared, audit, access, organization(+dto)                                      NEW
settlement → … D35 …, customers(root, +dto) ⊕
production → … D35 …, settlement::event ⊕      (debt-payment slip, D75; events only)
fiscal     → unchanged: shared, audit, access, organization(+dto)                           ⊖ D13 planned fiscal → customers
reporting  → shared, access, organization(+dto), identity(root), dining(root), catalog(root, +dto),
             orders(root, +event), fiscal(root, +dto, +event), settlement(root, +event), cash(root)   NEW
```

| rule | why | rejected alternative |
|---|---|---|
| `fiscal ↛ customers` (⊖) | Settlement already coordinates the sale. It reads the `CustomerSnapshot` from customers and passes it as the document's `Buyer` (P17 already uses an inline buyer). Fiscal keeps no link to a live customer record: a document needs only the snapshot (§8.2 "Documento emitido não depende de leitura… atual"). | fiscal → customers as D13 planned: an extra edge that buys nothing, and fiscal would read live customer data while it issues. |
| `customers ↛ orders, settlement, fiscal, cash` | Customers owns identity, limit and ledger only. Settlement "coordena a cobrança, sem duplicar saldo" (§8.1). | Customers listening to fiscal events to post debits: the debit would land after the sale commits, so two terminals could exceed the limit (§8.4). |
| `reporting` reads only, and nobody depends on it | §8.1: SalesRecord "nunca gera recebimento ou stock". Reporting calls only read-only facade methods (D84). | Reporting depending only on events: see D84. |
| `production → settlement::event` (⊕) | A debt payment prints a non-fiscal slip through the one print queue (D75), the same way `fiscal::event` already feeds document printouts. There is no cycle, because settlement never depends on production. | settlement → production: forbidden by D35 ("no module → production"). |
| `nothing → production` (D35) stays | The pending-work views are composed by the BackOffice client from the settlement, production and system reads, like the R2 table map. | A pending-work aggregator module: it would need `→ production`. |

Enforcement: each new `package-info.java` declares these lists, and `ModularityTests.modulesRespectTheirDeclaredBoundaries` checks them. `ArchitectureTests.forbiddenModuleDependenciesAreAbsent` gains the edges `fiscal ↛ customers`, `customers ↛ {orders, settlement, fiscal, cash, production}` and `nothing → reporting`. The new rule `reportingCallsOnlyReadOnlyFacadeMethods` fails when a reporting class calls a facade method that is not `@Transactional(readOnly = true)`. Each rule has an `ArchitectureRulesFireTest` fixture in `com.xavier.archfixture.*`. A rule written before its package exists uses `allowEmptyShould(true)`.

**D66 — New public facades** (root packages, D36: one per consumer concern; reads are `readOnly`; writes are `MANDATORY` and do not authorize). The PRD gives their signatures.
- `customers.CustomersApi`, for settlement: snapshot, lock account, reserve, release, post debit, post credit, reverse credit, balance.
- `settlement.SettlementFacts`, for reporting only (read-only): payment, refund and credit-charge facts, plus paged id listings for backfill.
- `fiscal.FiscalApi` additions: a generalized draft (source ORDER or PARTITION, `onAccount`, `customerId`, `businessDate`), `coverage(orderId)`, `documentFacts(documentId)`, `issuedDocumentIds(storeId, from, to, page)`.
- `orders.OrdersApi` additions: `voidLine(…)` for the coordinated void (D71), a richer `BillingLine` (`itemId`, `releaseId`, `servedByEmployeeId`, `confirmedAt`), `tableId` and `tableCode` on `OrderBillingView`, `voidFacts` and `voidIds`.
- `cash.CashApi` additions: `recordRefund`, `closeSessionForced`, `sessionDay(sessionId)`.
- `organization.OrganizationApi.findTerminal(UUID)` (R2 backlog). `dining.DiningApi.tableInfo(UUID)` (area of a table). `identity.EmployeeDirectory.summaries(ids)` (labels for exports).

**D95 — Change notices.** Partitions, credit charges and refunds notify the existing `ORDER` topic, and debt payments notify `PAYMENT_ATTEMPT`. `ChangeTopic` stays as it is: a notice says only "re-read" (D60), and the POS re-reads the order's settlement view either way.

---

## 3. Aggregates and state machines (§8.3)

As in R2, every transition is a method on the aggregate, and an illegal transition is a `ConflictException` subclass (409). Each machine has a plain unit test over all states and commands. The DB enforces what it can.

### settlement
| aggregate | states / transitions | invariants (how enforced) |
|---|---|---|
| `BillPartition` + `BillPartitionLine` (Conta dividida, §8.1) | `LIVE → CANCELLED`. The financial state (UNSETTLED / PARTIALLY_SETTLED / SETTLED) and `invoiced` are derived, never stored. Kind `ITEMS` (lines) or `AMOUNT` (a fixed amount, optionally `group_id`, `part_no` and `parts` for equal parts). | **Immutable once created.** To change one, cancel it and create it again. Cancel is refused once a payment, a credit charge, an open attempt or a document exists (422 `PARTITION_IN_USE`). For each order line: Σ quantity in LIVE ITEMS partitions ≤ net quantity. This is checked under `order_settlement FOR UPDATE`, because a cross-row sum is not a constraint. Line amounts follow D68. `UNIQUE(partition_id, order_line_id)`. A DB trigger refuses UPDATE and DELETE of lines. The first partition of an order calls `lockForPayment` (D49). |
| `PaymentAttempt` | Unchanged machine. It gains a **target** (D69): `ORDER`, `PARTITION` or `CREDIT_ACCOUNT`. | **One open attempt per target** (replaces R2's one per order): a partial unique index on `(target_type, target_key) WHERE status IN ('CREATED','PENDING','UNKNOWN')`. Reclassifying to FAILED is guarded by age (D81). |
| `Payment`, `PaymentAllocation` | A fact (CONFIRMED). | `UNIQUE(attempt_id)` stays. An allocation has a target (D69). Σ allocations = payment amount, checked by a deferred constraint trigger. |
| `CreditCharge` (credit sale) | `RESERVED → POSTED` or `RESERVED → RELEASED`. | At most one live (RESERVED or POSTED) charge per target (partial unique). amount > 0 and ≤ the target's balance at creation. POSTED ⇒ `document_id` and `ledger_entry_id` NOT NULL (CHECK). RELEASED only while the document is not ISSUED. |
| `Refund` | A fact (CONFIRMED). | Σ refunds of a payment ≤ the payment amount: checked under the lock, backed by a deferred trigger. Same method as the payment. Approver NOT NULL and ≠ operator (CHECK plus the use case). Immutable trigger. |
| `ReconciliationBatch` | `IMPORTED` (a fact). | `UNIQUE(method_code, file_sha256)`, so the same statement is never imported twice. Period `from ≤ to`. |
| `ReconciliationEntry` + `ReconciliationResolution` | An entry is a fact: `MATCHED`, `AMOUNT_MISMATCH`, `MISSING_INTERNAL`, `MISSING_PROVIDER` or `DUPLICATE_LINE`. It is open until it has a resolution, which is a fact. | `UNIQUE(batch_id, line_no)`. Partial `UNIQUE(payment_id) WHERE kind IN ('MATCHED','AMOUNT_MISMATCH')`: a payment is matched at most once across batches. MATCHED entries are created resolved. |

### customers
| aggregate | states / transitions | invariants |
|---|---|---|
| `Customer` (Cliente) | `active` and `blocked` flags (`@Version`). | Phones normalized to digits, with `UNIQUE(customer_id, digits)`. The tax id is not unique (a family can share one), but it is indexed. |
| `CustomerCreditAccount` (Crédito) | `credit_blocked` flag, `credit_limit ≥ 0`, `@Version`. | One per customer (PK `customer_id`). **The balance is never stored**: it is Σ ledger (D73). A limit change writes a `CreditLimitChange` fact and is audited. |
| `CreditReservation` | `ACTIVE → CONSUMED` or `ACTIVE → RELEASED`. | `UNIQUE(source_id)` (the charge id), so one reservation per charge. Partial index `(account_id) WHERE status='ACTIVE'`. |
| `CreditLedgerEntry` | A fact: `DEBIT` (credit sale, refund of a debt payment) or `CREDIT` (debt payment). | `amount > 0`. `UNIQUE(source_type, source_id, kind)`, so a repeated post adds nothing. Immutable trigger. |
| `CreditSettlement` | A fact: which CREDIT entry settles which DEBIT entry, and for how much. | Σ per debit ≤ the debit amount, and Σ per credit = the credit amount. Both are deferred trigger checks. |

### cash (amendments)
| aggregate | change |
|---|---|
| `CashMovement` | New types: **`REFUND`** (OUT, source `REFUND`, any method) and **`ADJUSTMENT`** (IN or OUT, any method, reason required, allowed on a **CLOSED** session whose day is not FINANCIALLY_CLOSED). `actor_account_id` is added, and a CHECK requires exactly one of the employee and account actors, because adjustments come from the BackOffice. The open-session trigger lets `ADJUSTMENT` through. The partial source unique index already covers `REFUND`. |
| `CashSession` | Adds `closed_by_employee_id`, `close_kind` (`OPERATOR` or `FORCED`) and `close_reason` (FORCED ⇒ reason). P22. |
| `CashDay` | Reopening from `FINANCIALLY_CLOSED` is now allowed (P19, R3 part). `cash_day_reopening` gains `from_status`. A reopened day accepts only corrections (P25). New-day policy per P28. |
| `CashClosing` | Adds `day_round` (the number of reopenings when it was created). The live unique index becomes `(register_id, cash_day_id, day_round) WHERE status IN ('DRAFT','SUBMITTED','APPROVED')`, so an APPROVED closing of an earlier round is kept and a new round can be approved (§8.3 "conserva fechos anteriores"). Approval re-computes the expected amount: if movements changed since submission, it returns 409 `CLOSING_STALE`. |

### fiscal (amendments)
| aggregate | change |
|---|---|
| `FiscalDocument` | `source_type ∈ {ORDER, PARTITION}`. New columns: `order_id` (for PARTITION), `on_account`, `customer_id` (from the snapshot) and `business_date`. `order_ref` is a stored generated column, `coalesce(order_id, CASE WHEN source_type='ORDER' THEN source_id END)`, so R2's ISSUED rows get it without an UPDATE (the immutability trigger is never fired). Index `(order_ref)`. A DRAFT can still receive a buyer. ISSUED rows stay immutable. |

### production (amendments)
| aggregate | change |
|---|---|
| `ProductionTicket` | Adds `routing = ROUTED | UNROUTED`. An UNROUTED ticket has `printer_id NULL` and no job (CHECK). `route` turns it ROUTED and creates its first job, once (D89). |

### identity
| aggregate | states | invariants |
|---|---|---|
| `SupportGrant` | `ACTIVE → EXPIRED` (by time) or `ACTIVE → REVOKED`. | At most 24 h. Only for accounts flagged `support`. A support account logs in only while a grant is active. Partial unique: one ACTIVE grant per account. |

---

## 4. Split and partial payment (money rules)

| id | decision | alternatives rejected / why |
|---|---|---|
| **D67 — BillPartition is an explicit, immutable allocation** | A partition is created by an explicit command and never edited. `ITEMS` takes quantities of order lines. `AMOUNT` takes a fixed amount of the **residual** (the due minus all LIVE partitions). Equal parts are N `AMOUNT` partitions created in one command, sharing a `group_id`. New consumption never changes an existing partition (§8.2): it lands in the residual. | (a) Editable partitions: an edit racing a payment needs a second version protocol, and cancel-and-recreate is enough at a table. (b) Client-side splitting only (paying by amount): two terminals could charge the same part twice (§4 "Alocações impedem cobrar duas vezes a mesma quantidade"). |
| **D68 — One allocation rule, `Money.allocate`** | **ITEMS:** for an order line with gross `G`, tax `T` and net quantity `Q`, a new share of quantity `q` takes `Money.allocate([q, Q_rem − q])[0]` of the line's **remaining** gross and of its remaining tax, where `Q_rem` is the quantity not yet in a LIVE partition. Its net is `gross − tax`. The last share of a line (`q = Q_rem`) therefore receives exactly the rest. Shares always sum to `G` and `T`, so the per-rate tax totals are preserved (§14 #4). With whole quantities and exact unit prices the result equals `LineAmounts.of(unitPrice, q, rate)`, so nothing is rounded. **Equal parts:** `Money.allocate(n)` of the residual. The leftover cents go to the lowest part numbers (that is `Money.allocate`'s tie-break), for example 100.00 / 3 → 33.34, 33.33, 33.33. **AMOUNT:** `amount ≤ residual`. **Cancelling** a partition returns its quantities and amounts to the remaining pool. Each later share is again computed over that pool, so the sums still hold. A residual document line gets the line's gross and tax minus the shares already **invoiced** in partition documents. | (a) `unitPrice × q` per share, with a residual absorbing the drift: two rounding points, and the drift lands on whichever document is issued last. (b) Proportional by amount for item shares: shifts tax between rates. The rule is in one pure function, `settlement.PartitionAmounts`, with unit and property tests. |
| **D69 ★ — Payment targets** | `PaymentAttempt`, `Payment` and `PaymentAllocation` gain `target_type ∈ {ORDER, PARTITION, CREDIT_ACCOUNT}` with `order_id` (nullable for CREDIT_ACCOUNT), `partition_id` and `credit_account_id`, and a CHECK that the target matches the type. Existing rows get `target_type DEFAULT 'ORDER'`, which is metadata only, so no fact row is updated. The **target key** is `coalesce(partition_id, order_id, credit_account_id)`. Balances are per target: **partition** = partition due − Σ allocations − refunds − live credit charges; **ORDER residual** = order due − Σ LIVE partition dues − the same terms for the ORDER target; **customer** = Σ open debits. An attempt amount must not exceed its target's balance (422 `AMOUNT_EXCEEDS_BALANCE`). One open attempt per target: an attempt in doubt on partition A does not block partition B, which is a different charge (§7.4 is about repeating the *same* charge). Order close still requires no open attempt on any of the order's targets. A debt payment may allocate to several debit entries, so `UNIQUE(payment_id)` on allocations becomes `UNIQUE(payment_id, target_type, target_key)`, and a **deferred constraint trigger** checks Σ allocations = payment amount at commit. Whether a payment was an advance (made before its document was issued) is **derived** (payment instant < document `issued_at`) and exposed in views for the market adapter (§8.2). | (a) A separate payment table per target: three copies of the D48 flow. (b) Keeping one open attempt per order: two diners paying their own partitions by card would wait on each other's terminal for no correctness gain. |
| **D70 — One document per item partition, plus a residual document at close** | An `ITEMS` partition may be invoiced on its own: `POST /partitions/{id}/documents`, `executeSteps` (D63), source `PARTITION`. This does **not** freeze the order, so the rest of the table keeps ordering (§8.2 "Novos consumos não alteram partições já emitidas"). Settlement guards invoiced quantities (D71). `AMOUNT` partitions are payment groupings and are never invoiced on their own. At close, D55 becomes: (1) **coverage** = `FiscalApi.coverage(orderId)` (quantity, gross and tax per source line over DRAFT and ISSUED documents); (2) if any net quantity is uncovered, `freezeForInvoice` and issue a **residual** `ORDER` document for exactly the uncovered quantities and amounts; (3) the close checks require, for every line, Σ covered quantity = net quantity and Σ document gross = due, with every document ISSUED, settled (paid + credit = due), no RESERVED credit charge, and no open attempt. When everything is already covered by partition documents, no residual document exists. | (a) A document per amount part: the lines would need fractional quantities (for example 1/3 of each line), which is regime-dependent (P31). (b) Freezing the order at the first partition document: the table could not order dessert after one diner left. |
| **D71 — Coordinated void within headroom (P32)** | Route `POST /api/v1/pos/orders/{id}/settlement/voids`, owned by settlement, with an approver (D40). It works with billing lock NONE or PAYMENT. In one transaction: `order_settlement FOR UPDATE` → headroom check → `OrdersApi.voidLine(orderId, expectedVersion, lineId, qty, reason, wasted, operator, approver)`. That method is `MANDATORY` and may bypass the PAYMENT lock only because its contract says the caller holds `order_settlement` and checked the headroom. Orders keeps the void fact, its event and its audit. The headroom rule: qty ≤ net − Σ qty in LIVE partitions (422 `QUANTITY_PARTITIONED`); the order is not frozen and the qty is not covered by any document (422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`); ORDER residual due after the void ≥ ORDER-target paid + credit + open attempt amounts (422 `VOID_EXCEEDS_UNPAID`: refund first). The R2 orders route `POST /orders/{id}/voids` stays and still refuses under the PAYMENT lock. The POS may always use the settlement route. | (a) Orders calling a settlement SPI from inside its void: orders would lock the order row before `order_settlement`, the reverse of D51, and could deadlock against a payment. (b) Moving the R2 route into settlement: two slices would write the same route, and the R2 end-to-end test would break between merges. |

**D68 amendment (S28, approved during the build).** *The rule.* A new ITEMS share of quantity `q` of a line whose pool (what no LIVE partition holds) has quantity `Q_rem`, gross `G_rem` and tax `T_rem` takes `G_rem − LineAmounts.of(unitPrice, Q_rem − q, rate).gross` and the same for tax, so the pool after every share is exactly the line amounts of what is left. Shares and pool telescope to the line, so documents still sum to the order's gross and tax per rate (§14 #4). *Why the change.* The rule above claimed that tax shares equal `LineAmounts.of(unitPrice, q, rate)` with whole quantities. They do not: 3 × 1.00 at 16 % split 1 + 1 + 1 gives tax 0.14 / 0.14 / 0.13, and the third share's net, 0.87, fails D59's exact recomputation (0.86). The chained `Money.allocate` also drifts on 3-decimal quantities. *The bound.* Fiscal checks allocated lines within a bound instead of D59 exactly. Settlement sets `DraftCommand.amountsAllocated` on every PARTITION draft and on the residual ORDER draft of an order that has or had partitions. An allocated line must have `net + tax = gross` exactly, no negative amount, gross within 1 minor unit of `round(unitPrice × q)`, and net within 2 of `round(gross / (1 + rate/100))`. Totals must equal Σ lines, and the flag is part of the DRAFT_MISMATCH content. Unpartitioned ORDER drafts keep D59 exactly (this also amends D59 for allocated drafts). *The guard.* Immutable shares plus arbitrary cancels make any fixed bound unreachable by a creation rule alone: the pool's error is a sum over whichever shares were cancelled. So under `order_settlement` settlement refuses, with 422 `SPLIT_ROUNDING_LIMIT`, any partition create, partition cancel or settled void that would leave a LIVE share or a line's pool outside the bound. A drafted document therefore never fails fiscal's check. The residual document has one line per uninvoiced LIVE share plus one line for the pool, so each document line is one bounded piece. *Evidence* (300 000 simulated lines per run, rates 0/5/16/17/23): the chained `allocate` reached gross 2 minor units with 3 shares on a 3-decimal line, and 3 with 20. The anchored rule never left gross ≤ 1 / net ≤ 1 without cancels (0 of 2.08 M lines); with 25 % cancels about 0.1 % of lines would exceed the bound, and those are what the guard refuses. *Settled voids.* The fiscal documents' tax per rate is authoritative. When a settled void empties a line's pool, the order's recomputed tax for that line may differ from the documents by at most one minor unit per such line; reconciliation (S31) and reporting (S32) read tax from the documents. The guard accepts an emptied pool only with no gross and at most one minor unit of tax, and the settlement view shows the difference per rate (`taxRoundingDifference`, zero otherwise). **⟶ Superseded by [D96](#d96): the documents' tax is no longer authoritative over the order's. The residual document absorbs the cent within the same rate, and when it cannot, a pending fiscal correction is recorded. The void and its guard are unchanged.**

---

## 5. Customers and credit ("conta corrente", §5, §7.7, §8.4)

| id | decision | alternatives rejected / why |
|---|---|---|
| **D72 — Customer and search** | `Customer` (name, `name_search`, tax id, email, notes, active), `CustomerPhone` (digits only, plus `digits_reversed`), `CustomerAddress` (kind BILLING or DELIVERY; delivery is used in R4). Search `GET …/customers?q=`: digits (at least 4) match a **phone suffix** with a prefix scan on `digits_reversed` (`text_pattern_ops` B-tree); text (at least 3 characters) matches a **name contains** with a `pg_trgm` GIN index on the unaccented lower-case name; an exact tax id uses a B-tree. The response is always a **candidate list** (at most 20), even with one match. The API has no "select" semantics (§5 "nunca selecionar automaticamente"). A PostgreSQL IT checks `EXPLAIN` for each pattern names its index (§8 "plano verificado"). | (a) `LIKE '%x%'` on a B-tree: a sequential scan, which §8 forbids. (b) An external search engine: a new service for a few thousand rows. **New DB extension:** `pg_trgm` (shipped in the PostgreSQL image's contrib, trusted since PG13, so the database owner can `CREATE EXTENSION`) is the only index type that serves *contains* search. `unaccent` is avoided: a generated column with `lower(translate(...))` over a fixed accent map does the job. |
| **D73 ★ — Credit account, reservation and ledger in `customers`** | **Balance = Σ DEBIT − Σ CREDIT** over `credit_ledger_entry`. It is never stored (§5 "saldo é uma projeção"; spec Etapa 3 "dívida e limite"). **Available = limit − balance − Σ ACTIVE reservations.** `CustomersApi.reserve(customerId, amount, chargeId)` takes `credit_account FOR UPDATE`, re-checks active, not blocked and available ≥ amount, then inserts the reservation. That lock is the defence against **two terminals putting the same customer over the limit at once** (§8.4). Posting a debit consumes the reservation in the same transaction. A debt payment posts a CREDIT and its `CreditSettlement` rows: FIFO over open debits by `occurred_at`, or the debits the client names. Limit changes are facts with reason and actor (`POST /credit-limit-changes`, §9). There is no endpoint that sets a balance (§9). | (a) A `balance` column updated in place: a second source of truth to reconcile, with no speed gain at this size (an index-only Σ over the account's entries). (b) Checking the limit without a lock (optimistic): two concurrent reservations both read "available". |
| **D74 — Credit sale = `CreditCharge` in settlement** | `POST /api/v1/pos/orders/{id}/credit-charges {customerId, amount, partitionId?}` uses `executeSteps` (D63). **s1** (tx): `order_settlement FOR UPDATE` → target balance and no open attempt → `CustomersApi.reserve` (`credit_account FOR UPDATE`) → insert `CreditCharge RESERVED` → the target's document: an ORDER target freezes the order and creates the residual draft; a PARTITION target creates its draft. Either way the buyer is the customer's snapshot and `on_account = true`. If the target's document is already ISSUED with another buyer or none: 422 `DOCUMENT_ALREADY_ISSUED`. **s2:** `FiscalApi.issue` with no tx. **s3** (tx): `order_settlement` → charge CAS RESERVED → POSTED → `CustomersApi.postDebit(chargeId, documentId, …)`. **Credit creates no cash movement and no `Payment`** (§7.7, §14 #4). The order's financial state counts RESERVED and POSTED charges as "crédito autorizado" (§8.3). Close requires them POSTED. If s2 fails: 422 `FISCAL_PENDING`, the reservation stays held, the charge appears in pendings, and a retry with the same key resumes. Cancel (`/credit-charges/{id}/cancel`) is allowed only while RESERVED with no ISSUED document. It releases the reservation. A reservation **never expires on its own**: releasing it automatically after the document was issued would let the account go over its limit. **Recovery:** `CreditChargeSweeper` posts the debit for each RESERVED charge whose document is ISSUED (a crash between s2 and s3). It is idempotent through `UNIQUE(source_type, source_id, kind)` on the ledger. **⟶ Superseded in part by [D97](#d97):** `on_account` now means that the document carries a credit share, and the document shows the payment breakdown. Only a document whose credit equals its total is labelled "A CRÉDITO" as a whole. | (a) Customers posting the debit from a `FiscalDocumentIssued` listener: the limit would be checked after the sale (a race) and the debt could lag the document. (b) A credit `Payment` row: §7.7 and §14 #4 say credit is not money received, and it would show in cash expectations. |
| **D75 — Debt payment received later ("receber posteriormente e imputar ao extrato", §8.5)** | `POST /api/v1/pos/customers/{id}/credit-payments {methodCode, amount, tendered?, debitEntryIds?}` creates a `PaymentAttempt` with target `CREDIT_ACCOUNT`. It follows the same R2 flow per method kind (cash immediate, external confirm, integrated D48). On SUCCEEDED, in one tx: `credit_account FOR UPDATE` → `Payment` + allocation → `CustomersApi.postCredit(paymentId, …)` → `CashApi.recordReceipt` (it is real money received, on the cash day of the session). amount ≤ open debt (422 `AMOUNT_EXCEEDS_DEBT`, P33). A non-fiscal payment slip prints through production (a `DOCUMENT` source is not used; a new `RECEIPT_SLIP` source). | Allocating to documents instead of ledger entries: the ledger is the customer's statement (§5 "extratos"), so documents are referenced *from* the debit entries. |
| **D76 — Lock order for R3** (amends D51) | `order_settlement` → `customers.credit_account` → `cash_session` / `cash_day` → `fiscal_document` → `fiscal_series`. Debt payments start at `credit_account`. Partition and credit commands take `order_settlement` first. No lock is held across a call that leaves the JVM. | A credit-account lock before `order_settlement`: a credit sale and a payment on the same order would take the locks in opposite orders. |

---

## 6. Refunds

**D77 — `Refund` is a settlement fact** ("Devolução é Refund com referência ao pagamento", §8.3).
`POST /api/v1/pos/payments/{id}/refunds {amount, reason}` requires the `PAYMENT_REFUND` permission and an approver who holds `PAYMENT_REFUND_APPROVE` (D40, P30). In one transaction: `order_settlement` (or `credit_account` for a debt payment) `FOR UPDATE` → refundable check → `Refund` → effect:
- **CASH:** a `REFUND` OUT movement in the **refunder's** open session (422 `NO_OPEN_CASH_SESSION`).
- **EXTERNAL_TERMINAL and INTEGRATED:** the operator performs the refund on the terminal or the provider portal and enters its reference. A `REFUND` OUT movement with the method code is still recorded, so the closing compares terminal totals (D52).
- **A refund of a debt payment:** `CustomersApi.reverseCredit`, which posts a DEBIT with source REFUND, so the debt reopens.

The refundable amount is `payment − Σ refunds`, and further:
- If the target has no ISSUED document covering it, any of that amount can be refunded.
- Otherwise only the overpayment (`paid + credit − due`) can be refunded. Anything more is 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER` (P31).

Paid amounts are `Σ payments − Σ refunds` everywhere (Bills, reports). R3 ships no integrated refund API: the `PaymentProvider` gains no `refund` method until a real provider exists (P10).
- *Rejected:* refunds as negative payments. They break `amount > 0`, the attempt state machine, and "reversões separadas" (§8.3).
- *Rejected:* building a provider refund port now, with no provider to test it against.

---

## 7. Reconciliation (§5 "reconciliação de adquirentes", §8.1 ReconciliationEntry)

| id | decision | alternatives rejected / why |
|---|---|---|
| **D78 ★ — Provider statements against payments** | **Input:** a `ReconciliationBatch` per imported statement (store, method, period, file SHA-256). The format is one canonical CSV: `occurred_at, amount, fee, reference, authorization_code, line_ref` (P37). An optional `StatementSource` capability on a `PaymentProvider` adapter fetches the same lines through `executeSteps` (the call runs with no tx, D48). Only a fake implements it in R3. **Matching**, in one tx at import, deterministic: (1) INTEGRATED by `provider_reference` or `provider_transaction_ref`; (2) EXTERNAL_TERMINAL by method + `authorization_code` + amount; (3) otherwise by method + amount + time window (±10 min), **only when there is exactly one candidate**, never by guessing (the same principle as §5's suffix search). The outcomes are MATCHED (fee recorded), AMOUNT_MISMATCH, MISSING_INTERNAL (the provider has it and we do not), MISSING_PROVIDER (we have it in the period and the provider does not), and DUPLICATE_LINE (already matched by an earlier batch). Payments count net of refunds. **Resolution** is a fact (`ACCEPTED`, `REFUNDED_AT_PROVIDER`, `ESCALATED`, each with a reason, `RECONCILIATION_MANAGE`). **Reconciliation never creates, edits or deletes a Payment, Refund or CashMovement.** It records differences, and corrections go through their own commands (§8.4 "cada efeito tem registo"). | (a) Auto-creating a "late payment" for MISSING_INTERNAL: money facts would appear without an operator, a session or a cash day. (b) Fuzzy matching on amount alone: two equal card payments in the same minute would be paired at random. (c) A per-acquirer import parser now: no acquirer is named (P10). |
| **D79 — Cash control** | **The CashClosing is the cash reconciliation** (expected Σ movements against counted, §8.1 "Fecho"). R3 adds: (1) `ADJUSTMENT` movements on a CLOSED session of a day that is not FINANCIALLY_CLOSED, BackOffice, `CASH_ADJUST`, with a reason (§8.3 "correções posteriores são movimentos auditados; não reescrever contagem original"); (2) approval re-computes the expected amount: changed → 409 `CLOSING_STALE`, so reject and resubmit (a new attempt row, R2 rule); (3) a financial reopen, `FINANCIALLY_CLOSED → OPEN` with `CASH_DAY_REOPEN` and an approver holding `CASH_CLOSING_APPROVE`; APPROVED closings are kept, and new closings get `day_round + 1`; (4) a forced session close (P22), owned by settlement because it must check payments in doubt (D37); (5) new-day policy P28: when a session opens after the store's cutoff while an earlier day is OPEN, a new `CashDay` is created and the earlier day refuses new sessions (422 `DAY_CLOSING_ONLY`); (6) a reopened day accepts only corrections (P25); (7) `GET /backoffice/cash/reconciliation` per day × register × method: expected, counted, adjustments, difference, justification and approval. | (a) Editing the stored count: forbidden by §8.3. (b) A separate "cash reconciliation" table: it would duplicate the closing lines ("não duplica dados em finance", §8.1). |
| **D80 — Documents against payments: a computed control, no table** | `GET /backoffice/reconciliation/documents?storeId&businessDate` lists exceptions computed from facts: an ISSUED document whose target is unpaid and not on account after its order closed (impossible by D70, so a non-empty list is a bug alarm); an `on_account` document without a POSTED charge; a POSTED charge whose ledger debit is missing; a CLOSED order whose coverage ≠ net quantity; a RESERVED charge older than 1 h; a DRAFT document older than 1 h. **⟶ Amended by [D96](#d96)** (a tax difference is explained only by a recorded pending correction, never by the split alone: `TAX_CORRECTION_PENDING`, `TAX_DRIFT_UNASSIGNED`) **and [D97](#d97)** (`SNAPSHOT_CREDIT_MISMATCH`). Settlement owns it because it already sees orders, payments, fiscal and customers. | Persisted findings: they would go stale and need their own reconciliation. |
| **D81 — Hardening payments in doubt** (R2 backlog) | (1) **The `PaymentProvider.status` contract:** `Failed` means *final*, so the provider guarantees the reference will never succeed. A new `ProviderOutcome.NotFound`, for a reference the provider does not know, counts as FAILED only for an UNKNOWN attempt older than `provider.acceptanceWindow()` (a default method, PT15M). It stays UNKNOWN otherwise. (2) **Automatic polling:** `InDoubtResolver` asks `status` for UNKNOWN attempts with backoff (1, 2, 5, 10, 30 min, then every hour). It records a final answer through the same CAS path as the manual resolve, so there is still exactly one Payment. (3) **Age guard:** a manual reclassification to FAILED requires the attempt to be older than `restaurantpro.settlement.reclassify-failed-min-age` (PT15M) **and** at least one provider status query since it became UNKNOWN. Otherwise 422 `RECLASSIFY_TOO_EARLY`. Reclassifying to SUCCEEDED has no age guard. (4) The gauge `payment_attempts_in_doubt{store}` (exists) and an alert after 30 min (§12). | (a) Treating NotFound as FAILED at once: a charge still in flight could later succeed after the customer was charged again. (b) Polling only by hand: §7.4 asks to query before repeating, and automatic polling clears most cases with no operator. |

*D81 note (S30 review):* only an **answered** status query (Succeeded, Failed, Pending or NotFound, counted as `answered_count`) satisfies (3); a timeout or a saturated call is a poll for the backoff but no answer. For a method whose provider bean is gone (it can never answer), FAILED needs only an age of `restaurantpro.settlement.reclassify-failed-without-provider-min-age` (PT24H), with the approver and reason of the normal reclassification.

---

## 8. Audit and support access

| id | decision | alternatives rejected / why |
|---|---|---|
| **D82 — Audit query API** (D20 promised it for R3) | `GET /api/v1/backoffice/audit/events?storeId&from&to&actorId&actorKind&action&entityType&entityId&page&size` (`AUDIT_VIEW`). **Scope:** `AccessPolicy.scopeOf(AUDIT_VIEW)` gives the installation-wide holder every row, including store-less rows (system, company); a store-scoped holder gets only rows of the stores they hold it in. Naming another store → 403 (D42 (a)). `from`/`to` are required, with a span ≤ 92 days (422 `RANGE_TOO_LARGE`). Sort is `occurred_at DESC, id DESC`, offset pages capped at 200 (D23). `GET /audit/entities/{type}/{id}` gives an entity's history, filtered to visible stores. `POST /audit/exports` streams CSV (`AUDIT_EXPORT`, at most 100 000 rows, otherwise 422) and **records an `AUDIT_EXPORTED` event** with the filters (§10 "autorização adicional para… exportações"). Plain reads are not audited. New index `(store_id, action, occurred_at)`: "all voids this week" is the common query. The secret-key guard becomes **recursive** over nested maps and lists (the ADR 0005 known limit). | (a) Keyset pagination: better at depth, but the bounded range keeps offsets small, and D23 is the project-wide shape. (b) A hash-chained audit log: tamper evidence against the DB administrator, which §10 treats as an infrastructure risk ("controlado por segregação"). Revisit if a regime requires it. |
| **D83 — Support access (P36)** | Identity: a `UserAccount.support` flag; `SupportGrant(accountId, validFrom, validUntil ≤ +24 h, reason, grantedByAccountId)`, `SUPPORT_ACCESS_MANAGE` (administrator). Login of a support account requires an ACTIVE grant. The session max-inactive is capped at the grant's remaining time. A job each minute expires grants and revokes their sessions (the existing per-user revoke). Every grant, revoke, expiry and login is audited. Actions are audited as that account, as usual. | A shared vendor password: it cannot be attributed per person, and §5 requires temporary and audited access. |

---

## 9. Reports (§5 "Relatórios", §8.6; `SalesRecord` / reporting, §8.1)

| id | decision | alternatives rejected / why |
|---|---|---|
| **D84 ★ — Projections fed by event-triggered pulls** | Each owner publishes a **thin** event in the fact's transaction (registry, D19): `FiscalDocumentIssued` (exists), `OrderLinesVoided` (exists), and new `PaymentRecorded`, `RefundRecorded`, `CreditChargePosted` (`settlement.model.event`, ids + store). A reporting `@ApplicationModuleListener` **pulls** the immutable fact through a read-only facade (`FiscalApi.documentFacts`, `SettlementFacts.*`, `OrdersApi.voidFacts`) and upserts its projection row keyed by the source id (`INSERT … ON CONFLICT DO NOTHING`), so it is idempotent. **Backfill and rebuild use the same code path:** `POST /backoffice/reports/rebuild {storeId, from, to}` deletes the store/range projection rows (projections, not facts) and pulls the paged id listings (`issuedDocumentIds`, `paymentIds`, …). This also loads R2 history, which predates reporting. Tables (`reporting` schema): `sales_record`, `sales_record_line`, `sales_record_tax`, `payment_record` (payments and refunds, signed), `credit_record`, `void_record`, `rebuild_run`. Dimensions are resolved at projection time and stored: the family (from the line's `release_id` through `CatalogApi.snapshot`, which is historically correct; parsed releases are cached in memory because a release is immutable), the area (`DiningApi.tableInfo`, the current area of the table, a documented approximation), the serving employee (the batch confirmer), and the terminal and mode. | (a) **Fat events** carrying every report field: two code paths (live and rebuild), plus contract churn on every new report column. (b) **SQL views over the module schemas:** always fresh, but it breaks D6 (cross-schema coupling), and the heavy joins run on every query. (c) **Polling by a timestamp cursor:** a transaction that commits late with an earlier timestamp is skipped (commit order ≠ timestamp order). (d) **Live aggregation through owner APIs:** slow over ranges, and joins across modules would be done in Java. |
| **D85 — Business day and timezone** | Every projection row stores `business_date`, plus `local_date` and `local_hour` in the store's zone **at the fact's instant**. They are computed once, so a later timezone change does not rewrite history, and DST is handled by `ZonedDateTime`. Attribution (P37): a document → its **order's business day** (passed into the draft, the new `business_date` column; an R2 document falls back to `BusinessDays.of(issued_at, zone, cutoff)`); a payment, refund or adjustment → the **cash day of its session** (`CashApi.sessionDay`); a void → its order's business day; a credit charge → its order's business day. With P28, an order's payments land in the cash day equal to its business day. Reports filter by `business_date` only, never by a UTC range. Hour-of-day groups by `local_hour`. | Filtering by `issued_at` in UTC: a day that crosses midnight would split, which §4 forbids ("dia comercial pode atravessar a meia-noite"). |
| **D86 — Freshness, finality, speed, export** | Every report response carries `asOf` (the newest projected fact) and `pendingFacts`, the count of incomplete publications for reporting listeners. The count comes from the shared `EventPublicationStats` (D88), backed by a partial index. `final = true` when every business date in the range has its `CashDay` FINANCIALLY_CLOSED (`CashApi`) and `pendingFacts = 0`. **Target** (measured in S33 with a seeded year of one store, about 150 k documents and 600 k lines): a 31-day report in < 1 s p95, a 1-day report in < 300 ms. Indexes are `(store_id, business_date, <dimension>)` per table. Daily rollup tables are added **only if** the benchmark misses the target. CSV export (`REPORT_EXPORT`) is audited (`REPORT_EXPORTED`). | Rollups from day one: a second projection to keep consistent before any number shows it is needed. |
| **D87 — Control reports come from the modules that own the data** | Cash closings and differences (cash, D79 (7)). Customer statement and aging 0–30, 31–60, 61–90, > 90 days (customers). Reconciliation (settlement, D78–D80). Audit (audit, D82). These read the facts directly and are always current. Reporting does not copy balances (§8.1 "não duplica dados"). **Credit never appears as money received** (§14 #4): `payment_record` holds only Payments and Refunds, and on-account amounts are a separate column fed from `credit_record`. | Copying cash and credit into reporting: two places that can disagree. |

---

## 10. Failure recovery (§7, §11, §14 #3, #5)

| id | decision | alternatives rejected / why |
|---|---|---|
| **D88 ★ — Event publications are recovered without a restart** (R2 backlog) | `shared.events.EventPublicationRecovery` (scheduled, D46): every `restaurantpro.events.resubmit-interval` (PT1M) it calls `IncompleteEventPublications.resubmitIncompletePublications(p -> older than PT5M && completion_attempts < 10)`. Past 10 attempts a publication stays FAILED and alerts. Startup republishing stays on. Listeners are idempotent (D19), so a resubmission that overlaps a slow original is safe. `CompletedEventPublications.deletePublicationsOlderThan(P30D)` runs daily. `EventPublicationStats` exposes gauges `event_publications{state="incomplete"|"failed"}` (the label is the listener's module, never an id; §12) and the per-listener pending count (D86). BackOffice `GET /api/v1/backoffice/system/event-publications?state` and `POST …/resubmit {publicationIds | olderThan}` (`SYSTEM_MONITOR`, audited). Index `(listener_id) WHERE completion_date IS NULL` (shared V1006). The exact Modulith 2.1 API names are confirmed in S19. The behaviour above is binding. | (a) Restart-only recovery (R2): a failed listener waits until the next deploy. (b) An own outbox table: duplicates the registry D19 chose. |
| **D89 — Print recovery** | (1) **Unrouted work is kept** (R2 backlog): when a destination has no route, or a terminal has no printer, the ticket is created `UNROUTED` with no job. It is listed in `print-jobs`/tickets with `status=UNROUTED` and counted in `production_unrouted_total`. `POST /production-tickets/{id}/route` (`PRODUCTION_OPERATE`, Idem) resolves the route now and creates the job once (CAS UNROUTED → ROUTED). A line with **no destination** is still not printed, by design (D56). (2) **`SubtotalRequested.requestId`** (R2 backlog): orders draws a UUIDv7 per subtotal command (the Idempotency-Key replay returns the stored response, so a retry does not draw again), and production dedupes the SUBTOTAL ticket on it. A deliberate second subtotal prints, and a redelivered event does not. A publication stored by R2 without `requestId` falls back to the R2 content digest. (3) **SSE on shutdown** (R2 backlog): `ChangeNotifier` becomes a `SmartLifecycle` that completes every emitter in `stop()` before the web server's graceful shutdown, so shutdown no longer waits 30 s. | Dropping unrouted work and logging it (R2): the order was confirmed but the kitchen never sees it (§4 "falha de impressora não perde… o pedido"). |
| **D90 — The restart matrix and PITR** | One IT per crash point. Each is restarted with a second context on the same DB (the `EventRepublicationTest` pattern) and proves exactly one logical effect: (a) after a batch commit, before production; (b) after a credit document is issued, before the debit (sweeper); (c) after a partition document is issued, before the response (`executeSteps` takeover); (d) during a print send (UNKNOWN, no automatic resend); (e) an integrated charge with UNKNOWN through a restart (polling resolves it once); (f) a reporting listener failing twice (runtime resubmission completes it). **Deploy:** WAL archiving (`archive_mode=on`, `archive_timeout=300s`, which meets the 15 min RPO, §11) to a volume, a nightly `pg_basebackup`, `deploy/pitr-restore.sh --target-time`, and a drill test that restores to a timestamp in an isolated container. It reuses the `age` encryption from D32. **⟶ Superseded in part by [D98](#d98):** encryption is mandatory in production. The archive_command encrypts WAL into the `pg-wal-archive` volume, the identity is never on the server, and a scheduled restore drill includes key recovery. | Leaving PITR for later: §11 sets RPO ≤ 15 min, and pg_dump alone gives about 24 h. |

---

## 11. Consistency in R3 (re-checking §7 and §8.4; "IT" = PostgreSQL integration test)

**D91 — Named races and their proofs.**

| race / failure | prevention | proof |
|---|---|---|
| Two terminals partition the same quantity | `order_settlement FOR UPDATE`, then Σ LIVE quantity ≤ net quantity | IT `PartitionRaceTest` (two threads → one 201, one 422 `QUANTITY_ALREADY_PARTITIONED`) |
| Two terminals pay the same partition | Lock + balance per target + one open attempt per target | IT `PartitionDoublePaymentTest` |
| Two terminals put the same customer over the limit | `credit_account FOR UPDATE` in `reserve` | IT `CreditLimitRaceTest` (limit 100, two charges of 60 on different orders → exactly one) |
| A void races a payment on the same order | Both take `order_settlement` first; the void re-checks headroom | IT `VoidVersusPaymentRaceTest` |
| A crash between issuing a credit document and posting the debit | Sweeper + ledger `UNIQUE(source)` | IT `CreditChargeRecoveryTest` |
| A refund retried, or two refunds racing on one payment | Idempotency-Key + lock + deferred Σ trigger | IT `RefundRaceTest` |
| The same statement imported twice | `UNIQUE(method_code, file_sha256)` → 409 with the batch id | IT `StatementImportTest` |
| A payment matched by two batches | Partial `UNIQUE(payment_id)` on matched kinds → DUPLICATE_LINE | IT `StatementImportTest` |
| An adjustment between submit and approve | Approval re-computes the expected amount → 409 `CLOSING_STALE` | IT `ClosingStaleTest` |
| Allocation sums | Deferred constraint trigger | IT `AllocationSumTest` (a manual insert that breaks it fails at commit) |
| Split preserves totals, taxes and quantities (§14 #4) | D68 + D70 | Property IT `SplitPreservesTotalsTest`: random orders (whole and 3-decimal quantities, several rates), random partitions, all documents → Σ gross = due, Σ tax per rate = order tax per rate, Σ quantity per line = net quantity |
| Credit not shown as cash (§14 #4) | No credit path reaches `recordReceipt`; the `cash_movement` source CHECK allows only PAYMENT and REFUND | IT `CreditIsNotCashTest` (a credit sale → 0 movements; the payments report's received total excludes it) |
| A timeout stays reconcilable (§14 #3) | D81 | IT `InDoubtPollingTest` (UNKNOWN → poll → one Payment; NotFound inside the window → still UNKNOWN) |
| Printer and restart without logical duplication (§14 #5) | D89, D90 | IT `RestartMatrixTest` (a–f) |
| A retried close or residual draft after a crash re-places the tax cent differently (D96) | `UNIQUE(order_id, source_line_id)` on `tax_rounding_adjustment`; a retry re-applies the stored rows instead of computing them again | IT `TaxRoundingResidualTest` (resume) |
| Two resolutions of one pending correction (D96) | `UNIQUE(adjustment_id)` on `tax_correction_resolution` → 409 `TAX_CORRECTION_RESOLVED` | IT `TaxCorrectionResolutionTest` |
| A payment lands between the snapshot refresh and the issue (D97) | Not prevented, by design: no lock is held across the issue call (§7.3). The snapshot is the position at the refresh | IT `DocumentPaymentBreakdownTest` (documents the window) |
| A charge is cancelled between the refresh and the issue (D97) | `clearOnAccount` nulls the snapshot, so the issue refuses (`PAYMENT_SNAPSHOT_MISSING`) and the retry refreshes it; CHECK `on_account = (credit_total > 0)` | IT `PaymentSnapshotTest` |

---

## 12. Permissions (append-only to `access.Permission`, D27)

**D92** — these constants are added in S19 with `V3010__identity_r3_permissions.sql`. The administrator role gets all of them (`RoleSeedTest`). An empty column means administrator only.

| permission | for | Gerente | Sala | Caixa | Auditor |
|---|---|---|---|---|---|
| `BILL_SPLIT` | create and cancel partitions | ✓ | ✓ | ✓ | |
| `CUSTOMER_VIEW` | search, view, statement, aging | ✓ | | ✓ | ✓ |
| `CUSTOMER_MANAGE` | create and edit customers, contacts and addresses | ✓ | | ✓ | |
| `CREDIT_LIMIT_MANAGE` | limit changes, credit block and unblock | ✓ | | | |
| `CREDIT_SALE` | credit charges and their cancel | ✓ | | ✓ | |
| `PAYMENT_REFUND` | request a refund | ✓ | | ✓ | |
| `PAYMENT_REFUND_APPROVE` | be the refund approver | ✓ | | | |
| `CASH_ADJUST` | adjustments on closed sessions | ✓ | | | |
| `CASH_DAY_REOPEN` | reopen a financially closed day (with an approver) | ✓ | | | |
| `CASH_SESSION_FORCE_CLOSE` | close an abandoned session (P22) | ✓ | | | |
| `RECONCILIATION_VIEW` | batches, entries, controls, in-doubt list | ✓ | | | ✓ |
| `RECONCILIATION_MANAGE` | import statements, resolve entries | ✓ | | | ✓ |
| `REPORT_VIEW` | reports | ✓ | | | ✓ |
| `REPORT_EXPORT` | CSV export, audited | ✓ | | | ✓ |
| `REPORT_REBUILD` | rebuild projections | | | | |
| `AUDIT_EXPORT` | audit CSV export | | | | ✓ |
| `SUPPORT_ACCESS_MANAGE` | support grants | | | | |

`AUDIT_VIEW` (R1) is granted to Gerente (store-scoped) and Auditor. Customers are company-wide (P38): BackOffice customer endpoints call the new `AccessPolicy.requireInAnyStore(p)`, and POS endpoints call `requirePos(p)`. If P24 is accepted, `CASH_EXPENSE` becomes the *approver* permission, and the operator records the expense with `CASH_OPERATE`.

---

## 13. Flyway blocks

**D93** — existing blocks (D61), next free numbers. The settlement sub-ranges keep slices that run in parallel from colliding, and they merge in ascending order:
- shared `V1006` (S19)
- audit `V1103+` (S26)
- identity `V3010` (S19), `V3011+` (S25)
- orders `V6008+` (S23)
- production `V7006+` (S24)
- cash `V8007+` (S21)
- settlement `V9005–V9019` (S28), `V9020–V9029` (S29), `V9030–V9039` (S30), `V9040–V9049` (S31)
- fiscal `V10007+` (S22)
- customers `V11001+` (S20)
- reporting `V14001+` (S32)
- **D96–D97 (amendment):** settlement `V9050–V9059` (S34) and `V9060–V9069` (S35, reserved: S35 is expected to need none), fiscal `V10009+` (S35). S36 (D98) has no migration.

There are still no cross-module FKs (D6). The money-fact triggers follow the R2 pattern. **No migration UPDATEs an immutable fact row:** new columns use `DEFAULT` or a stored generated column (D69, fiscal `order_ref`).

---

## 14. R2 backlog: disposition

**D94**

| backlog item | R3 decision |
|---|---|
| SubtotalRequested lacks a request id | **Take**: D89 (2), slices S19 + S24 |
| Runtime resubmission of incomplete event publications | **Take**: D88, S19 |
| OrganizationApi terminal lookup | **Take**: `findTerminal`, S19 |
| Manual reclassify-to-FAILED needs an age guard | **Take**: D81 (3), S30 |
| `PaymentProvider.status` contract | **Take**: D81 (1), S30 |
| An abandoned cash session cannot be closed | **Take**: P22, D79 (4), S21 + S30 |
| SSE delays shutdown by about 30 s | **Take**: D89 (3), S19 |
| Unrouted production work is dropped | **Take**: D89 (1), S24 |
| open-orders has no tableId | **Take**: D66, S23 + S28 |
| catalog, dining and fiscal controllers → `access.IdempotentRequests` | **Take**: mechanical, S19 |
| D63 / ADR 0018 still Proposed | **Ask**: P39 (recommendation: ratify) |
| Audit secret guard checks top-level keys only (ADR 0005) | **Take**: D82, S26 |
| Expense approver (open since ADR 0011) | **Ask**: P24 |
| Support access (D29) | **Ask, then take**: P36, D83, S25 |
| Bolsas, envelopes, shifts (P16) | **Ask, then defer**: P34 |
| Menus and macros (P20) | **Ask, then defer to R4**: P35 |

---

## 15. Failure modes (R3 additions)

| failure | behaviour |
|---|---|
| Fiscal issue fails during a credit sale or a partition document | 422 `FISCAL_PENDING`. The reservation and charge are held, and the DRAFT is listed in pendings. A retry with the same key issues it with no gap. No cash or ledger effect until the document is issued. |
| Crash after a credit document is issued | The sweeper posts the debit once. The order close waits for it. |
| Provider unreachable during polling | The attempt stays UNKNOWN with backoff. The order and session stay blocked, as in R2. Manual reclassification to FAILED is possible after the age guard. |
| A provider statement line has no internal payment | MISSING_INTERNAL, open until it is resolved. No money fact is created. It appears in the reconciliation report. |
| A reporting listener fails | The publication is resubmitted every minute (at most 10 times), then FAILED with an alert. Reports show `pendingFacts > 0` and `final = false`. A rebuild repairs any projection. |
| No printer route | The ticket is UNROUTED, visible and routable later. The order is untouched. |
| Database lost | Restore to a point in time from WAL, within the 15 min RPO (D90). |
| A support grant expires mid-session | Its sessions are revoked within a minute, and the next request gets 401. |
| A settled void leaves a tax cent and no unissued document of that rate can take it (D96) | The void and the close succeed. The issued documents stay unchanged, and a `PENDING_CORRECTION` row plus an audit event are recorded. Reconciliation lists `TAX_CORRECTION_PENDING` until a correction resolution is recorded. |
| A payment is confirmed between the snapshot refresh and the issue (D97) | The document shows the payments known at the refresh. The settlement view shows the live status. Nothing is flagged, because the document states the position at issue. |
| A credit charge is cancelled between the refresh and the issue (D97) | `clearOnAccount` also clears the snapshot. The issue refuses the document with `FISCAL_PENDING` (`PAYMENT_SNAPSHOT_MISSING`), and the retry refreshes and issues it. |
| Production backup keys are missing or the permissions are loose (D98) | PostgreSQL refuses to start, and `backup.sh` / `basebackup.sh` exit non-zero without writing. `verify-backups.sh` alerts. Nothing is ever written in plaintext as a fallback. |
| The operational age identity is lost (D98) | The recovery identity, held by a second custodian, decrypts every backup. The quarterly key-recovery drill proves this. |

---

## 16. R3 acceptance criteria (observable)

| # | criterion | §14 |
|---|---|---|
| A16 | Split by items into two partitions, each invoiced on its own, then close: a residual document covers the rest. Σ gross of the 3 documents = due, Σ tax per rate = the order's, and Σ quantity per line = net quantity (property IT, including 3-decimal quantities). | #4 |
| A17 | Equal parts of 100.00 into 3 → 33.34 / 33.33 / 33.33. Two terminals paying the same part at once → one Payment and one movement; the other is refused (422/409). Paying a part twice with the same key → one Payment. | #3, #4 |
| A18 | Quantity already in a LIVE partition → 422. Two concurrent partitions of the same quantity → one wins. A partition with a payment cannot be cancelled. | §8.2 |
| A19 | After a partition document is issued, a new round is still accepted. The issued document is unchanged, and the new lines are in the residual. | §8.2 |
| A20 | Credit sale: reserve → document (buyer = customer, on account) → debit. Zero cash movements, zero Payments. The order closes SETTLED. The payments report shows it on account, not as received. Limit 100 with two concurrent charges of 60 → exactly one succeeds. | #4, §8.4 |
| A21 | A debt payment later: one receipt movement, ledger CREDIT allocated FIFO, a correct running balance on the statement. A replay with the same key → no duplicate. Paying more than the debt → 422. | #3 |
| A22 | Fiscal failure during a credit sale → 422; a retry with the same key → one document and one debit. A crash after issue → the sweeper posts exactly one debit. | §7.6 |
| A23 | A cash refund → one REFUND OUT movement in the refunder's session, approver audited. Refunding an invoiced, non-overpaid amount → 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`. Refunding a debt payment reopens the debt. | §8.3 |
| A24 | A void after partial payment within headroom → accepted, with a void ticket. Beyond headroom → 422 `VOID_EXCEEDS_UNPAID`. On invoiced quantity → 422. | P32 |
| A25 | Timeout → UNKNOWN → polling resolves to exactly one Payment. NotFound inside the acceptance window → still UNKNOWN. Reclassify to FAILED too early → 422. The same statement imported twice → one batch. Its entries are matched, mismatched or missing as expected, and no money fact changes. | #3 |
| A26 | An adjustment on a closed session changes the expected amount, and approving the old submission → 409 `CLOSING_STALE`. A financial reopen keeps the APPROVED closing, and a round-2 closing is approvable. A manager force-closes an abandoned session with a count and a reason (audited), but not while a payment is in doubt. After the cutoff with yesterday open, a session opens in the new day with a warning (P28). | §8.3, §8.4 |
| A27 | A Waiter → 403 on the audit API. A store-A auditor never sees store-B rows. A span > 92 days → 422. An export creates `AUDIT_EXPORTED`. A nested secret key in details is rejected. | #9, §10 |
| A28 | After a seeded day: sales by item, hour, employee and area match the documents; taxes per rate = Σ `fiscal_document_tax`; receipts by method = Σ (PAYMENT_RECEIPT − REFUND) movements per method; credit is separate. A rebuild from empty gives identical results. `pendingFacts > 0` while a listener is held, and `final` once the day is financially closed and nothing is pending. | #4 |
| A29 | Restart matrix (D90 a–f): each crash point ends with exactly one logical effect. A listener that failed once is completed by runtime resubmission without a restart. | #5, #3 |
| A30 | An unrouted ticket is visible, and routing it later prints once. The same subtotal requested twice prints twice; a redelivered event prints once. Shutdown does not wait for SSE. | #5 |
| A31 | Suffix "4567" returns every matching customer as candidates, even when there is only one. `EXPLAIN` uses the suffix and trigram indexes. | §5, §8 |
| A32 | `verify()` passes with D65. An injected `fiscal → customers`, `customers → settlement`, `X → reporting` or reporting → write facade fails the build. | §6 |
| A33 | PITR drill: restore to a chosen time in an isolated container. The data matches that time, and the RPO is shown. | §11, #8 |
| A34 | Support grant for 2 h: login works. After expiry: 401 and sessions revoked. Every step is audited. | §5 |
| A35 | (D96) 2 × 1.00 at 23 %, one unit in partition A, the other voided (settled): the order's 23 % tax is 0.19 and A's share is 0.18. A not invoiced → the residual line of A's share carries 0.19, Σ document tax at 23 % = 0.19, and one ABSORBED row is audited. A invoiced on its own, with another 23 % line in the residual → that line absorbs it. A invoiced and nothing else at 23 % → the close succeeds, A's document is unchanged, a PENDING_CORRECTION row names it, and the documents control lists `TAX_CORRECTION_PENDING` until it is resolved. A retried close gives the same rows and the same draft. | #4, §8.4 |
| A36 | (D97) Cash 20.00, then credit 30.00 on a 50.00 order → the document prints `Pagamento misto — Numerário: 20.00; A crédito: 30.00`, status MIXED, `on_account = true`, without the whole-document "A CRÉDITO". Credit 30.00 first (issued at once) → `Pagamento misto — A crédito: 30.00` and `Saldo em aberto: 20.00`. Full credit → "A CRÉDITO", ON_ACCOUNT. The snapshot never changes after issue, and a reprint prints the same block. The settlement view's `paymentStatus` follows later payments. | #4, §7.7 |
| A37 | (D98) In production mode, without two recipients, PostgreSQL does not start and no backup is written in plaintext. Every file in the WAL volume, in `backups/` and off the box is age ciphertext. An identity file on the server, or a loose file mode, makes the scripts refuse. The drill restores to T with the operational key and again with the recovery key alone. A wrong key fails cleanly. | #8, §10, §11 |

---

## 17. Process and what I need from you

The same gates as R1 and R2. G1 is this document, and G2/G3 are the [PRD](../prd/r3-stage3.md) slices. Each slice is built by the developer agent in its own worktree with TDD (G4: `rtk mvn clean test` green with 0 skipped), then reviewed by a fresh code-reviewer (G5). S33 integrates, and an architecture review (G6) follows. You merge (G7). **Every slice PR targets `main`. There are no stacked PRs**: a slice starts from `main` after the slices it depends on have merged. After approval, S33 writes ADRs 0019–0024 for D65, D69, D73, D78, D84 and D88, plus 0025–0027 for D96, D97 and D98, updates the ADR 0018 status per P39, adds the new terms to CONTEXT.md (BillPartition, CreditCharge, CustomerCreditAccount, CreditReservation, CreditLedgerEntry, CreditSettlement, Refund, ReconciliationBatch/Entry, SalesRecord, residual document, headroom, support grant), and updates backend-conventions.md (lock order D76, target key, read-only reporting rule, recursive audit guard).

1. Approve the R3 scope (§1) and D64–D95, or override them by id. Check **D65**, **D69**, **D70**, **D73**, **D74** and **D84** in particular.
2. Answer P22–P39, or accept the defaults. **P24, P28 and P32 change R2 behaviour.**
3. Confirm that approving this also counts as G2/G3 for the PRD.

---

## 18. Amendments after the build (D96–D98)

Three decisions by Xavier Francisco on 2026-11-06, after S19–S32 merged. Each is binding as written below. Where a decision supersedes earlier text, that text carries a "⟶ Superseded" marker. Slices: [PRD S34–S36](../prd/r3-stage3.md#group-6-amendments-s34--s36-then-s35).

<a id="d96"></a>
**D96 ★ — The tax cent of a settled void: absorbed in the residual document, otherwise a pending fiscal correction.** It supersedes the "Settled voids" paragraph of the D68 amendment ("the fiscal documents' tax per rate is authoritative") and D80's rule that the split's drift is an explained difference.

*User decision:* "Allow the void, but reconcile the rounding difference. Recalculate using the defined tax rounding policy and allocate any residual cent deterministically within the same tax rate. Keep the adjustment auditable. If a fiscal document has already been issued, preserve it and use the applicable correction process."

*Where the cent comes from.* A settled void (D71) that empties a split line's pool (pool quantity 0) leaves `δ = pool tax`. This is the tax of the line, recomputed by orders (`LineAmounts.of(unitPrice, netQty, rate)`, per-line rounding under the single `Money` rule), minus the Σ of its LIVE shares. The existing guard keeps `|δ| ≤ 1` minor unit, and `δ > 0` means the documents would carry too little. Example: 2 × 1.00 at 23 %, one unit in partition A (share tax 0.37 − 0.19 = 0.18), the other unit voided → the line's tax is now 0.19 and A carries 0.18, so δ = +0.01. **The void stays allowed, and its guard (`SPLIT_ROUNDING_LIMIT`) is unchanged.** The target is that Σ document tax per rate equals the order's tax per rate, which is Σ of its lines' recomputed tax.

*The rule.*
1. **When:** only when the **residual ORDER document is drafted**. That happens in close s1, in `POST /orders/{id}/documents`, and in s1 of an ORDER-target credit sale: the only drafts taken with the order frozen (billing lock INVOICE). After the freeze, no void, partition, cancel or partition document can change a line. Placement runs under `order_settlement`, in the drafting transaction, before `FiscalApi.createDraft`. Nothing is placed at void time.
2. **Where (deterministic):** each drifting line L, taken in billing-view line order, places its δ on **one** line of the residual draft with the **same `ratePercent`**. The candidates are tried in this order:
   - first, the draft lines sourced from L itself (its uninvoiced LIVE shares, by partition `created_at`, then `id`);
   - then every other draft line of that rate, in draft line order.

   The first candidate that still passes `PartitionAmounts.withinFiscalBound(qty, unitPrice, rate, gross, tax + δ)` takes it: `tax += δ`, `net −= δ`, gross unchanged. A line may take several drifts while it stays within the bound. Draft totals are re-summed, and `amountsAllocated` is true (every drifting order has partitions). Fiscal's bounded check is therefore met by construction, and fiscal needs no change.
3. **No money moves.** Gross never changes, so dues, partitions, payments, credit charges, cash and the headroom are untouched. Only the tax/net split of one document line changes.
4. **When nothing can absorb it**, the δ becomes a **pending fiscal correction**. That happens when the rate has no line on the residual, when none of its lines stays within the bound, or when there is no residual document at all because partition documents cover every quantity. The last case is detected in close s3, after `requireInvoiced` and before `OrdersApi.close`. The row names the document to correct: the residual, when it holds a line of L; otherwise the ISSUED partition document that holds L's newest share (partition `created_at` desc, then `id` desc). The issued document is **never changed** (§8.2, V10005). The void and the close succeed. R3 issues no corrective document (P31).
5. **The record, auditable.** New table `settlement.tax_rounding_adjustment`, one row per drifting line, append-only (trigger):
   - columns: `id, order_id, store_id, business_date, source_line_id, rate_percent, amount (signed δ ≠ 0), currency, kind CHECK IN ('ABSORBED','PENDING_CORRECTION'), document_id NOT NULL, target_line_id, target_partition_id (NULL = a pool line), actor_kind, actor_id, created_at`;
   - constraints and indexes: `UNIQUE(order_id, source_line_id)`, CHECK ABSORBED ⇒ `target_line_id` NOT NULL, index `(order_id)`, and partial index `(store_id, created_at) WHERE kind = 'PENDING_CORRECTION'`;
   - it is written in the same transaction as the draft (or as close s3), with the audit actions `TAX_ROUNDING_ABSORBED` and `FISCAL_CORRECTION_PENDING` (entity `ORDER`, details: source line, rate, amount, document, target).

   A retried s1 **reads the stored rows and re-applies them** rather than placing again, so the redraft is identical and no `DRAFT_MISMATCH` occurs.
6. **Clearing a pending correction.** A pending row is cleared by a `settlement.tax_correction_resolution` fact:
   - columns: `id, adjustment_id UNIQUE, action CHECK IN ('CORRECTED'), correction_reference NOT NULL, reason NOT NULL, actor_kind, actor_id, at`, append-only; a trigger refuses an ABSORBED target;
   - endpoint: `POST /backoffice/reconciliation/tax-corrections/{id}/resolutions`, with `RECONCILIATION_MANAGE@store`, an Idempotency-Key, and the audit action `TAX_CORRECTION_RESOLVED`;
   - the reference identifies the corrective document produced by the market's correction process. In the market run, the adapter's corrective-document flow writes this resolution itself (actor SYSTEM, reference = the new document's id), and that document also makes the per-rate difference zero.

   R3 has **no accept or write-off action**: whether a regime tolerates a one-cent difference without a document is a market-run question.
7. **`taxRoundingDifference` and `OrderTaxDrift`.** Each per-rate item becomes `{ratePercent, difference, absorbed, pendingCorrection, corrected, unassigned}`:
   - `difference` is the raw Σ of the emptied pools' tax, as built;
   - `absorbed`, `pendingCorrection` (open) and `corrected` are Σ of the rows of each kind or state;
   - `unassigned = difference − absorbed − pendingCorrection − corrected`. It is non-zero only on an OPEN order whose residual is not drafted yet.

   The view also gains `taxRoundingAdjustments[]` (the rows, with their resolution). `OrderTaxDrift.of(orderId)` returns the same items.
8. **Reconciliation (`DocumentsControl`, closed orders).** Per rate, `D = lines' tax − ISSUED documents' tax`, and the expected value is `pendingCorrection + corrected`:
   - `D = expected` with an open pending row → one `TAX_CORRECTION_PENDING` exception per row (documentId, lineId, amount);
   - `D = expected` with every row resolved → an `ExplainedRoundingDifference`, which gains `correctionReference`;
   - `unassigned ≠ 0` → `TAX_DRIFT_UNASSIGNED` (a bug alarm);
   - any other non-zero `D` → `TAX_MISMATCH`.

   The split's drift alone no longer explains anything. Reporting keeps reading tax from the documents (D84), which now equals the order's tax except for open pending corrections.

*Alternatives considered.*
- (a) Refuse the void (`SPLIT_ROUNDING_LIMIT`): rejected by the user.
- (b) Keep the documents authoritative and the difference "explained" (as built): rejected by the user.
- (c) Place the cent at void time on a LIVE share: shares are immutable and can still be cancelled, so the adjustment would have to follow cancels, and the pool formula would need cross-line terms.
- (d) Place it on each partition draft as it is issued: the table is still open, and a later cancel returns a share to the pool, so the per-line telescoping (D68) breaks.
- (e) Change the order line's tax instead: §8.4 fixes the tax at confirmation.

*Why this rule.* The residual draft is the one moment when nothing about the order can change any more. A single, deterministic and idempotent placement there keeps gross fixed, reuses fiscal's bound, and keeps the per-line coverage exact wherever the drifting line still has an uninvoiced share.

<a id="d97"></a>
**D97 ★ — The payment breakdown on the document, a snapshot at issue.** It supersedes S22's "the internal adapter prints 'A CRÉDITO' on an on-account document" and D74's reading of an on-account document as a whole-document credit sale.

*User decision:* "Do not label the whole invoice exclusively 'A CRÉDITO'. Show the breakdown: 'Pagamento misto — Numerário: X; A crédito: Y', including the outstanding balance. Payment status should reflect partial payment."

*What is known at issue, and what only later.*
- **Credit is final at issue.** A charge is created only before its target's document is ISSUED (`DOCUMENT_ALREADY_ISSUED`), and it is cancelled only while no document is ISSUED.
- **Payments**: only those confirmed on the document's targets before the issue appear on it. Later payments do not, for example after an advance partition document, or the cash that follows a partial credit sale. The live settlement view shows them.
- **Refunds after issue** can only be of an overpayment (D77), and they are never shown on the document.

*The rule.*
1. **The targets a document covers.** A PARTITION document covers its partition. The residual ORDER document covers the ORDER target plus every LIVE partition without a document of its own (AMOUNT partitions, and ITEMS partitions not invoiced separately). CREDIT_ACCOUNT payments are never on a document.
2. **`PaymentSnapshot(status, methods[], credit, outstanding)`** (`fiscal.model.dto`):
   - `methods` is `{methodCode, label, amount}` per method: Σ payments − Σ refunds on the covered targets, positive amounts only, ordered by the method's first confirmation instant (then by code). `label` is the payment method's name at the snapshot (the seeded CASH method is "Numerário").
   - `credit` is Σ of the live charges (RESERVED or POSTED) on the covered targets.
   - `outstanding = gross − Σ methods − credit`. A negative value is impossible under D71/D74, and it is refused as a bug.
3. **Status**, with P = paid, C = credit and G = gross (one function, `PaymentStatus.of(G, P, C)`):

   | status | condition |
   |---|---|
   | `UNPAID` | P = 0, C = 0 |
   | `PARTIALLY_PAID` | C = 0, 0 < P < G |
   | `PAID` | C = 0, P = G |
   | `ON_ACCOUNT` | C = G |
   | `MIXED` | 0 < C < G (outstanding may be > 0) |

   The same function gives the **live** `paymentStatus` on `OrderSettlementView` (over every target of the order, G = due) and on `PartitionView`. `financialState` (§8.3) is unchanged, so MIXED and ON_ACCOUNT are SETTLED once nothing is outstanding.
4. **How it reaches fiscal** (settlement → fiscal only, D65):
   - **The refresh.** A new `settlement.service.SettlementIssuer.issue(pos, orderId, documentId)` runs one short transaction: `order_settlement FOR UPDATE` → compute the snapshot → `FiscalApi.recordPayments(documentId, snapshot)` (MANDATORY). It then calls `FiscalApi.issue` with no transaction. It is the only settlement caller of `issue`, which an ArchUnit rule enforces. The partition document, close, `/documents` and credit-sale paths all go through it.
   - **On the DRAFT.** `recordPayments` replaces the DRAFT's snapshot, and it is a no-op returning the stored snapshot on an ISSUED document. Issue passes the stored snapshot to the adapter (`IssueRequest.payments`, `IssuedDocument.payments`). The existing ISSUED trigger, plus the same trigger on the new rows, makes the snapshot **immutable after issue**, so a reprint prints exactly the same thing.
   - **No issue without a snapshot.** Issue refuses a DRAFT that has none: 422 `FISCAL_PENDING` with `lastError = PAYMENT_SNAPSHOT_MISSING`, retryable, because the retry refreshes it. `clearOnAccount` also clears the snapshot. Documents issued before S35 have none and print as before.
5. **When a document is `on_account`.** It is on account exactly when its snapshot's `credit > 0`, enforced by CHECK `on_account = (credit_total > 0)` once a snapshot exists. Its buyer is then the customer snapshot, and the ledger debit references it. The S22 sticky rule and `clearOnAccount` are unchanged. **"A CRÉDITO" as a whole-document label appears only for `ON_ACCOUNT`** (C = G).
6. **The printout of the internal adapter**, after the `TOTAL` line, one logical line each (the printer wraps). Amounts are printed with `toPlainString()`, like the other lines. Methods come in snapshot order, joined by `"; "`, with credit always last:

   | status | lines |
   |---|---|
   | `PAID`, one method | `Pagamento — Numerário: X` |
   | `PAID`, several methods | `Pagamento misto — Numerário: X; Cartão: W` |
   | `PARTIALLY_PAID` | `Pagamento parcial — Numerário: X`, then `Saldo em aberto: Z` |
   | `UNPAID` | `Saldo em aberto: Z` |
   | `ON_ACCOUNT` | header `A CRÉDITO` (as built), then `Pagamento — A crédito: Y` |
   | `MIXED` | `Pagamento misto — Numerário: X; A crédito: Y`, then `Saldo em aberto: Z` when Z > 0 (with no method paid: `Pagamento misto — A crédito: Y`) |

   The em dash is U+2014 with a space on each side, exactly as the user wrote it. "Saldo em aberto" is my wording for "outstanding balance".
7. **Interactions.**
   - **S29 credit sales:** a charge for the full balance gives ON_ACCOUNT, and a partial one gives MIXED.
   - **S22:** `on_account` keeps its meaning of "carries a credit share".
   - **Reporting:** unchanged, because money comes from `payment_record` and credit from `credit_record` (D87). `sales_record.on_account` is never summed as credit, and `DocumentFacts` is unchanged.
   - **BackOffice:** a new readOnly `FiscalApi.paymentSnapshot(documentId)` feeds the document detail (`payments` block).
   - **D80** gains `SNAPSHOT_CREDIT_MISMATCH`: an ISSUED document of a closed order whose snapshot credit ≠ Σ of the POSTED charges naming it (a bug alarm, since credit is final at issue).

*Alternatives considered.*
- (a) Fiscal reads the payments from settlement when it issues: this needs a fiscal → settlement edge, which closes a cycle with settlement → fiscal.
- (b) Take the snapshot only when drafting (s1): it is stale when a FISCAL_PENDING retry runs hours later.
- (c) Keep the whole-document "A CRÉDITO" and add a separate payment slip: rejected by the user.
- (d) A payment status on the document that is updated as payments arrive: this breaks ISSUED immutability (§8.2). The live status belongs to the settlement view.

*Why this rule.* The document states the position at issue: credit exactly, payments as of the refresh, which is milliseconds before numbering. It is immutable and reprinted identically, while settlement remains the one live source.

<a id="d98"></a>
**D98 ★ — Production backups are encrypted at rest and in transit, the keys are held apart, and restores are tested.** It supersedes D32's "optional encryption with `age`" and D90's "It reuses the `age` encryption from D32". It also supersedes the README rules that encryption is optional and that the `pg-wal-archive` volume is plaintext.

*User decision:* "Yes, for production backups. Require encryption at rest and in transit, store encryption keys separately from backups, restrict access, and regularly test restoration—including key recovery."

*The rule.*
1. **Production mode.** `RESTAURANTPRO_ENVIRONMENT ∈ {production, test}` is read from the process environment, otherwise from `deploy/.env` (parsed with grep, never sourced). When it is unset, it is **`production`**, the same fail-safe default as `RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED=false`. Any other value makes the scripts exit. `test` keeps today's behaviour (plaintext allowed, with a WARNING) for developer stacks only. The drills run in **production** mode with throwaway keys.
2. **Keys.**
   - **Recipients:** `deploy/backup-recipients.txt` (or `BACKUP_AGE_RECIPIENTS_FILE`), in age `-R` format. Production requires **at least two distinct X25519 recipients**: an *operational* key and a *recovery* key, each held by a different custodian. Only these public keys are on the server. The file is git-ignored, owned by root or the backup user, mode ≤ 0644, and not writable by group or others.
   - **Identities are never on the server.** Backup runs refuse when `BACKUP_AGE_IDENTITY` is set, or when any file of 64 KiB or less under `deploy/` contains `AGE-SECRET-KEY-1`.
   - **Restores:** `restore.sh` and `pitr-restore.sh` take `--identity FILE`. The file must be mode 0600 or 0400, owned by the caller, outside `deploy/` and outside the backup directory (for example on a tmpfs), and it is bind-mounted read-only into the age container only.
   - **Recipient change:** `verify-backups.sh` stores the recipients file's SHA-256 in `<dir>/RECIPIENTS_SHA256`, and a change fails until `--accept-recipients-change` is run (audited in the log). This catches a silent swap to an attacker's key. Rotation: add the new key, keep the old one until every backup encrypted only to it has aged out of retention, then remove it.
3. **At rest.**
   - **(a) WAL on the server.** `postgres/archive-wal.sh` encrypts in the archive_command: `age -R recipients` to `<f>.age`, plus `<f>.sha256` (the plaintext's SHA-256), both fsynced. The `.sha256` is renamed first and the `.age` last, so the `.age` marks the copy complete. The "already archived" check compares the sidecar, because ciphertext is never byte-stable. The `pg-wal-archive` volume therefore holds only ciphertext. PostgreSQL needs `age`, so compose builds `deploy/postgres/Dockerfile`: `FROM postgres:18.6-alpine` (the D4 pin) plus `apk add age=<pinned>`, tagged `restaurantpro-postgres:18.6-r1`.
   - **(b) Base backups and logical dumps** are encrypted to every recipient. `--wal-only` copies the `.age` and `.sha256` files **as they are**, without re-encrypting them.
   - **(c) Startup check.** In production, a preflight in the postgres entrypoint refuses to start PostgreSQL without a valid recipients file (fail closed, at install time, like the fiscal check). `backup.sh` and `basebackup.sh` exit non-zero, writing nothing, when no recipients are configured.
   - **(d) Staging.** Plaintext staging exists only on the database host, where the live database already is in plaintext. It is created mode 0700 outside `<dir>` and removed by a trap.
   - **(e) Legacy plaintext.** The live database volume is not a backup: full-disk encryption of the host is recommended in the README and not enforced. `basebackup.sh --encrypt-legacy` encrypts S27's plaintext files (in the volume and in `<dir>`) and plaintext logical archives in place and removes the plaintext. A new base backup follows.
4. **In transit.**
   - Backup data never crosses a network except the off-box copy. `pg_basebackup` and `pg_dump` run through `docker exec` over the local socket, and PostgreSQL publishes no port (D32). A remote backup client is unsupported; if one is ever added, it must use `hostssl` with `sslmode=verify-full`.
   - The backend's JDBC connection stays on the internal Docker network of the same host, without TLS: there is no network exposure, and TLS would add a certificate lifecycle.
   - The new `deploy/offbox-sync.sh` is the only off-box copier. It runs `rsync` over **ssh** with `BatchMode=yes`, `StrictHostKeyChecking=yes`, a pinned `deploy/offbox_known_hosts` and key authentication only, or it copies to a local removable disk. In production it refuses a network filesystem (`findmnt` FSTYPE nfs*, cifs or smb*) and any file that is not age ciphertext, apart from the markers and sidecars. The destination's `authorized_keys` must use `rrsync -wo -no-del`, so the server can neither read nor delete copies off the box; `--check-append-only` proves this with a canary. Each successful run writes `<dir>/LAST_OFFBOX_SYNC`.
5. **Access.** The scripts keep `umask 077`. In production they **refuse rather than fix** a backup directory or subdirectory whose mode is not 0700, files that are not 0600, an owner other than the effective uid, or a group- or world-writable recipients file or script. The error message gives the `chmod`/`chown` command to run. The volume stays `postgres:postgres 0700`.
6. **Regular restore tests, including key recovery.**
   - **(a)** `deploy/verify-backups.sh` runs on the server without any key, every 15 min from cron, and exits non-zero to alert (§12 "backup em atraso"). It checks:
     - the archiver: `pg_stat_archiver.failed_count` is not increasing, and the last archive is ≤ 15 min old when writes happened;
     - the newest base backup is ≤ 26 h old, and the WAL chain is continuous from the oldest kept base;
     - every file in the volume and in `<dir>` starts with `age-encryption.org/v1`, apart from the markers and sidecars, and has its sidecar;
     - the permissions, the recipients fingerprint, and that `LAST_OFFBOX_SYNC` is ≤ 30 min old.
   - **(b)** `deploy/verify-restore.sh --from <off-box dir> --identity <key>` runs **weekly on a restore host**, never on the server. That host holds the operational identity and needs only Docker. It decrypts the newest base backup and WAL, then `pitr-restore.sh --target-time latest` into an isolated container. It checks `pg_verifybackup` before the start, recovery reaching the end of the WAL, a clean `pg_amcheck --all`, and the Flyway history version. It also restores the newest `backup.sh` archive with `restore.sh` into a scratch container and checks it. It checks that `age-keygen -y identity` is one of the copied recipients. It writes `drill-<UTC>.json` (base, WAL reached, RTO, key fingerprint).
   - **(c) The quarterly key-recovery drill** is `verify-restore.sh --key-recovery`, run on a **clean machine** with the **recovery** identity, which the second custodian retrieves from escrow by the README procedure. It proves:
     1. the recovery key exists and can be retrieved by someone other than the operator, within the RTO (§11: 2 h), with the time measured;
     2. it matches a current recipient;
     3. on its own, with no operational key and no server, it decrypts the newest base backup, its WAL and the newest logical backup, and the restore completes.

     The README holds the drill log template: date, custodians, key, backup, result and times.
   - **`age` on machines without it** (the build machine has none): every script calls one wrapper, `deploy/lib/age.sh`. It uses the host's `age` when its version is at least the pinned one, and otherwise runs `docker run --rm -i --network none restaurantpro-postgres:18.6-r1 age …`, with identities bind-mounted read-only.

*Alternatives considered.*
- **For the WAL volume:**
  - (a) An encrypted host volume (LUKS): it protects only against theft of a powered-off disk, while root on the running host and any copy of the volume see plaintext. It cannot be set up or tested portably by the Compose package.
  - (b) Encrypt only at `--wal-only` time (as built): the volume stays plaintext for up to 5 minutes per segment, and indefinitely if the sync stops.
- **For keys:**
  - (c) A single key with escrowed copies: one lost or corrupted key loses every backup, and rotation needs re-encryption.
  - (d) Symmetric passphrases (`age -p`): the secret would have to be on the server.
- **For in transit:** (e) Plain rsync daemon or SMB mounts: unauthenticated destinations, which an attacker could also delete.
- **For tests:** (f) Restore tests on the server: they need the identity on the server, which breaks the key separation.

*Why this rule.* With recipient-only keys on the server, a stolen server or backup copy reveals nothing. Two custodians remove the single key as a point of failure. Fail-closed checks at install time keep a misconfigured installation from running unencrypted. The drills prove, with only the off-box copy and the recovery key, what §11 and §14 #8 ask for.

*Assumptions and open points (none blocks building):*
- The server is Linux (it relies on `findmnt` and GNU `stat`).
- The off-box destination supports ssh and `rrsync`. With a removable disk only, `--check-append-only` is skipped, and the README says so.
- The custodians and the escrow medium (for example a sealed paper key or a password manager) are chosen at installation, and the README records who holds them.
- Retention off the box is the destination's own job, and it must keep at least the server's retention.
