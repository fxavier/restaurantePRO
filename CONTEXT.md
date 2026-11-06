# restaurantPRO — domain glossary

Canonical terms from spec §8.1. Code uses the English name; the Portuguese term is what users say. One concept, one name: do not add synonyms ("site", "restaurant", "location" all mean Store).

| Code name | PT term | Module | Meaning |
|---|---|---|---|
| CompanyProfile | Empresa | organization | The single legal entity of this installation. Exactly one row. Created at bootstrap. |
| Store | Loja | organization | Operating unit of the company. Has timezone, business-day cutoff, currency. `storeId` scopes operations and permissions. |
| Terminal | Terminal | organization | A registered POS station. Enrolled once with a one-time code; then identified by a device token. |
| BusinessDay | Dia comercial | organization (rule), cash (CashDay) | A trading day that may cross midnight; starts at the store's cutoff time. |
| UserAccount | Conta | identity | Login credential (BackOffice password). |
| Employee | Empregado | identity | Operational person; logs in on POS with code + PIN. |
| EmployeeStoreAssignment | Atribuição a loja | identity | Employee × Store × Role. Many per employee. |
| Role | Função | identity | Named, editable set of Permissions. |
| Permission | Permissão | access | Code-defined capability (enum), append-only, checked server-side by `AccessPolicy`. |
| Approver | Aprovador | identity | Second employee whose credentials authorize a critical action; recorded in audit. |
| RoomArea | Área / sala | dining | Dining room, terrace, counter. |
| RestaurantTable | Mesa | dining | Physical table in a RoomArea. |
| TableOccupation | Ocupação de mesa | dining | One use of a table by one Order: OPEN → CLOSED (reason ORDER_CLOSED, ORDER_CANCELLED or MOVED). At most one open per table. |
| Family / Subfamily | Família / Subfamília | catalog | Grouping of items for POS navigation. |
| Item / ItemVariant | Artigo / Variante | catalog | Commercial product and its variants. Stock items belong to inventory, not catalog. |
| Unit | Unidade | catalog | Sale unit (piece, kg…). |
| TaxCategory / TaxRate | Categoria / Taxa de imposto | catalog | Configurable tax data; no country seeded. |
| PriceList / ItemPrice | Lista de preço / Preço | catalog | Price per item(-variant) per list. |
| ProductionDestination | Destino de produção | catalog (reference), production (R2) | Kitchen/bar/pantry zone an item is routed to. |
| ConfigurationRelease | Publicação | catalog | Frozen catalog snapshot: DRAFT → VALIDATED → PUBLISHED → RETIRED. |
| Order | Pedido / conta | orders | One bill of a store: DINE_IN (at a table), COUNTER or TAKEAWAY. Lifecycle OPEN → CLOSED (only via settlement) or OPEN → CANCELLED (nothing billed). Its business day is fixed at opening. |
| OrderLine (DRAFT / CONFIRMED) | Linha (rascunho / confirmada) | orders | A DRAFT line is server-side and may be edited or deleted; it is not a fact. A CONFIRMED line carries its snapshot and is immutable (DB trigger). |
| OrderBatch | Ronda / lote | orders | A round: the drafts confirmed together, numbered per order. CONFIRMED → DISPATCHED once production received it. Never re-sent. |
| Line snapshot | Instantâneo da linha | orders | Item, variant, code, description, unit price, tax category, code and rate, release, catalog version and destination, copied from the catalog snapshot at confirmation. Republishing never changes it. |
| Net quantity | Quantidade líquida | orders | Confirmed quantity − Σ voids − Σ transfers out. Derived, never stored. The bill is Σ line gross at net quantity. |
| OrderLineVoid | Anulação | orders | A fact: a quantity voided from a confirmed line, with reason, wasted flag, operator and approver. |
| Transfer | Transferência | orders | A fact: quantities moved to another OPEN order of the store. The target gets CONFIRMED copies with no batch, so production never sees them twice. |
| Merge | Juntar contas | orders | Transfer everything to another order, then cancel the emptied source. |
| Bill-reducing change | Alteração que reduz a conta | orders | A void or an outgoing transfer. Refused (422 `ORDER_IN_SETTLEMENT`) once a payment is under way. |
| Billing lock | Bloqueio de faturação | orders | `NONE → PAYMENT → INVOICE`, independent of lifecycle. PAYMENT (from the first attempt) refuses bill-reducing changes; INVOICE (document drafted, "frozen") refuses every change. |
| Preview price | Preço provisório | orders | The price the current catalog gives a DRAFT line (`preview = true`). Only confirmation fixes a price. |
| Version claim | Reserva da versão | orders | Moving an order's `@Version` on first (`OrderLookup.claim`), so the transaction is its only writer; several orders are claimed in ascending id order. |
| Table lock | Mesa bloqueada | dining | A flag (with who, when, reason): refuses new lines, rounds and incoming transfers; payment and close stay allowed. |
| Subtotal | Consulta de mesa / subtotal | orders, production | A printed provisional bill; the occupation records `subtotal_printed_at`. Not a fiscal document. |
| Catalog snapshot | Instantâneo do catálogo | catalog | The typed, parsed published release of a store (`CatalogApi`), the only catalog that orders read. |
| Printer | Impressora | production | A store's ESC/POS printer (TCP 9100) with code page and mode. |
| PrinterMode | Modo: Normal / Espera / Ignorar / Redirecionar | production | NORMAL prints; HOLD keeps jobs QUEUED; IGNORE suppresses new work; REDIRECT sends it to another printer of the store (no chains). |
| DestinationRoute | Rota de destino | production | Store × production destination code → printer. A line with no destination or no route is not printed. |
| TerminalPrinter | Impressora do terminal | production | Terminal → printer for subtotals and documents. Insert-only: reassigning a terminal elsewhere is refused. |
| ProductionTicket | Talão de produção | production | A fact: the logical ticket per source (BATCH, VOID, SUBTOTAL, DOCUMENT, TEST) × destination. Unique on that key, so a redelivered event makes no second ticket. |
| PrintJob | Trabalho de impressão | production | One physical delivery attempt: QUEUED → PROCESSING → SENT, RETRY_WAIT, FAILED or UNKNOWN. A retry is a new job. SENT means the bytes were written, not that paper came out. |
| Suppression | Supressão | production | A ticket created with `suppressed = true` and no job, because its printer is IGNORE. Visible, not a failure. |
| Copy | Cópia | production, fiscal | A reprint of a ticket or document: a new job of kind COPY, printed with "CÓPIA". Never a new document, payment or movement. |
| CashRegister | Caixa (registadora) | cash | A store's cash drawer. At most one OPEN session per register. |
| CashDay | Dia de caixa | cash | One business day of a store: OPEN → OPERATIONALLY_CLOSED → FINANCIALLY_CLOSED. Created by the first session of the day. |
| CashDayReopening | Reabertura do dia | cash | A fact (with approver): an OPERATIONALLY_CLOSED day back to OPEN. An event, not a state. |
| CashSession | Sessão de caixa | cash | One operator on one register: OPEN → CLOSED, with an opening float. At most one OPEN per register and per operator in a store. |
| Blind count | Contagem cega | cash | The operator's count per method at session close, recorded without seeing the expected amount. Immutable. |
| CashMovement | Movimento de caixa | cash | A fact: OPENING_FLOAT, PAYMENT_RECEIPT, CASH_IN, CASH_OUT, EXPENSE, REFUND or ADJUSTMENT. One receipt per payment (unique source). Immutable. |
| CashClosing | Fecho de caixa | cash | Per register × day: DRAFT → SUBMITTED → APPROVED or REJECTED; expected vs counted per method, justification when they differ. A resubmission is a new row. |
| PaymentMethod | Meio de pagamento | settlement | Company-wide data: code, name, kind CASH, EXTERNAL_TERMINAL (operator confirms) or INTEGRATED (provider port). |
| PaymentAttempt | Tentativa de pagamento | settlement | CREATED → PENDING → SUCCEEDED, FAILED, CANCELLED or UNKNOWN. Persisted before any provider call. An open or UNKNOWN attempt blocks new attempts and closes. |
| Payment | Pagamento | settlement | A confirmed, immutable fact: one per succeeded attempt. |
| PaymentAllocation | Imputação | settlement | Where a payment goes: its target (R2: the full amount to its order; R3: several debits of a debt payment, Σ = the payment). |
| OrderSettlement | Liquidação do pedido | settlement | The per-order lock row (no balance) that serializes payments, document issue and close. |
| FinancialState | Estado financeiro | settlement | UNSETTLED, PARTIALLY_SETTLED or SETTLED, derived from due vs paid. Never stored. |
| Reclassification | Reclassificação | settlement | Resolving an UNKNOWN attempt by hand (`PAYMENT_RESOLVE` + approver + reason), audited, instead of a provider query. |
| FiscalSeries | Série fiscal | fiscal | A store's numbering series per document type; one active per type. Gapless: the number is taken inside the committing transaction. No API sets it. |
| FiscalDocument (SALE) | Documento fiscal (venda) | fiscal | DRAFT → ISSUED. R2 issues one generic SALE per order, with company, store, buyer and line snapshots. ISSUED is immutable. |
| Fiscal pendency | Pendência fiscal | fiscal, settlement | A DRAFT that failed to issue, with its error: listed in pendings, payments kept, order close refused (`FISCAL_PENDING`). |
| Non-certified adapter | Adaptador não certificado | fiscal | The `internal` fiscal adapter: not for production; needs `allow-non-certified`, health shows NON_CERTIFIED, printouts say "DOCUMENTO NÃO FISCAL". |
| Actor kind | Tipo de ator | access | ACCOUNT (BackOffice login) or EMPLOYEE (POS PIN login); SYSTEM in audit for writes with no session. |
| PosContext | Contexto POS | access | Employee, terminal and store of the POS session, returned by `requirePos`. POS writes never take these from the request. |
| Change notice | Aviso de alteração | shared | An SSE `{topic, id}` sent after commit. Carries no data: the client re-reads through the authorized GET. |
| Coded error | Erro com código | shared | A 409 or 422 `ProblemDetail` with a stable `code` (and named extra properties) that clients switch on. Never renamed. |
| Step lease | Concessão de passo | shared | The time-limited claim on an Idempotency-Key used by a multi-step command (`executeSteps`). Live → 409; expired → may be taken over. |
| Login throttle | Limitação de login | identity | BackOffice attempts per username + address (10, reset on success) and failures per address (30): 15 min block, 429 with `Retry-After`. |
| mustChangePin | PIN a alterar | identity | Set by a manager's PIN reset: the next POS login must choose a new PIN before it gets any authority. |
| Customer | Cliente | customers | Company-wide (P38): name, phones, addresses, tax id. Searched by phone suffix; several matches are always offered as candidates, never guessed. |
| CreditAccount | Conta corrente | customers | A customer's limit, block flag and ledger. Balance = Σ DEBIT − Σ CREDIT, never stored; available = limit − balance − Σ ACTIVE reservations. |
| CreditReservation | Reserva de crédito | customers | Credit held for one charge under `credit_account FOR UPDATE`: ACTIVE → CONSUMED (debit posted) or RELEASED. The defence against two terminals passing the limit. |
| CreditLedgerEntry | Movimento de conta corrente | customers | A fact: DEBIT (a credit sale, naming its document) or CREDIT (a debt payment, or the reversal of a refunded one). Append-only. |
| CreditSettlement | Liquidação de dívida | customers | Which debits a CREDIT pays: FIFO by `occurred_at`, or the debits the client names. |
| CreditCharge | Venda a crédito | settlement | Putting (part of) an order or partition on a customer's account: RESERVED → POSTED (debit) or RELEASED. Final once its target's document is ISSUED. Never a Payment, never a cash movement. |
| BillPartition | Divisão da conta | settlement | A part of one order's bill: ITEMS (quantities of lines, 3 decimals) or AMOUNT (an equal part or a sum). LIVE → CANCELLED (only unpaid and uninvoiced). A payment target of its own. |
| Payment target | Alvo do pagamento | settlement | What an attempt pays: ORDER (its residual once partitioned), PARTITION or CREDIT_ACCOUNT (a debt payment). One open attempt per target. |
| Residual document | Documento residual | settlement, fiscal | The ORDER document drafted at close (or `/documents`): every quantity not on a partition's own document, covering the ORDER target and the LIVE partitions without a document. Drafted with the order frozen. |
| Headroom | Margem para anular | settlement | What a settled void may still remove: the ORDER residual after the void must stay at or above what its target was paid, put on account or has in flight (else 422 `VOID_EXCEEDS_UNPAID`; refund first). |
| Settled void | Anulação com pagamentos | settlement, orders | A void after part of the bill is paid, within headroom, with an approver, never of an invoiced or partitioned quantity (P32). |
| Tax rounding adjustment | Ajuste de arredondamento | settlement | The tax cent a settled void leaves on a split line (D96), placed once on a residual draft line of the same rate (ABSORBED: tax/net change, gross never). Append-only, audited. |
| Pending fiscal correction | Correção fiscal pendente | settlement, reconciliation | A tax rounding adjustment no unissued line could absorb: it names the issued document to correct, which is never changed, and is listed as `TAX_CORRECTION_PENDING` until a correction resolution (the corrective document's reference) is recorded. |
| Payment breakdown / snapshot | Discriminação do pagamento | fiscal, settlement | The payments, credit and outstanding on the targets a document covers, recorded just before issue by `SettlementIssuer` and immutable after: "Pagamento misto — Numerário: X; A crédito: Y", "Saldo em aberto: Z". |
| Payment status | Estado do pagamento | fiscal, settlement | UNPAID, PARTIALLY_PAID, PAID, ON_ACCOUNT (all on credit) or MIXED (part on credit), from gross, paid and credit (`PaymentStatus.of`). Fixed on a document; live on the settlement views. Distinct from FinancialState. |
| Refund | Devolução | settlement | A fact referencing a Payment: its own method, a REFUND OUT movement in the refunder's session, an approver. Only of what no issued document covers, or of an overpayment; refunding a debt payment reopens the debt. |
| ReconciliationBatch / ReconciliationEntry | Lote / linha de reconciliação | settlement (reconciliation) | One imported provider statement (store, method, period, file digest), and one entry per line or missing payment: MATCHED, AMOUNT_MISMATCH, MISSING_INTERNAL, MISSING_PROVIDER, DUPLICATE_LINE. Never changes a money fact. |
| SalesRecord | Registo de venda | reporting | The projection of one issued document (lines, taxes) for reports, with payment, credit and void records; rebuilt from the facts, never a source of money or stock. |
| Support grant | Acesso de suporte | identity | A time-limited (≤ 24 h), reasoned authorization for a support account to log in; it expires by itself, ends its sessions, and every step is audited. |
| Operational key / recovery key | Chave operacional / de recuperação | deploy | The two age identities whose public recipients encrypt every production backup: the operational one restores weekly on a restore host; the recovery one, held by a second custodian, proves quarterly that it alone restores. Neither is ever on the server. |
| AuditEvent | Evento de auditoria | audit | Append-only record: actor, approver, action, entity, store, terminal, instant, reason. |
| Money | Valor monetário | shared | BigDecimal + Currency, HALF_UP at line level, largest-remainder allocation. |
| IdempotencyRecord | Chave de idempotência | shared | Stored (scope, key, request hash, response) for repeatable commands. |
