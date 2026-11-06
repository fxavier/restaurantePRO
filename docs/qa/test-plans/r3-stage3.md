# Test plan: R3 (Stage 3) — split, credit, refunds, reconciliation, audit, reports, failure recovery

Owner: QA. Sources: spec §5, §7, §8.2–§8.6, §10, §11, §14; [r3-decisions.md](../../design/r3-decisions.md) §16 (A16–A37) and §18 (D96–D98); [PRD S33](../../prd/r3-stage3.md#s33--integrate-alone-after-every-slice-has-merged). Last updated 2026-11-06 on branch `s33-integrate`.

## Scope

In: bill partitions (items with 3-decimal quantities, amounts, equal parts), partition and residual documents, the settled void within headroom and its tax cent (D96), credit sales, the credit ledger and debt payments, refunds, payments in doubt (polling and reclassification), forced session close, the new-day policy (P28), financial reopen and closing rounds, provider statements and the documents control, reports and rebuilds, the audit query and export, support access, the restart matrix (D90 a–h), production backup encryption and restores (D98).

Out (other releases or not built): promotions, stock, loyalty, corrective fiscal documents (P31, market run), a certified fiscal adapter (B4), real printers (B5), the frontends (no SPA exists yet: every §8.5 flow is driven over the HTTP API instead of Playwright).

## Risks, deepest first

1. **Money and fiscal totals across a split**: leftover cents, two rates, cancels, voids after payment; Σ documents must equal the order (A16, A35).
2. **Credit**: the limit raced from two terminals; credit counted as cash; a crash between the document and the debit (A20–A22).
3. **Payments in doubt**: a second charge after a timeout; reclassifying too early; a session or day closed over an UNKNOWN (A25, A26).
4. **Restart**: a crash at any step leaving zero or two logical effects (A29, §14 #5).
5. **The printed document**: the D97 breakdown text and its immutability (A36).
6. **Reconciliation inventing money**, or attributing a payment to the wrong business day (A25, D78).
7. **Reports disagreeing with the facts**, or a rebuild disagreeing with live (A28).
8. **Permissions and store scope** on the new endpoints (A27, §14 #9).
9. **Backups**: plaintext in production, keys on the server, a restore that does not restore (A33, A37, §14 #8).

## Scenarios and the cheapest level that proves each

Levels: **U** unit/model test; **M** module IT (`@ApplicationModuleTest` + PostgreSQL); **S** system test over HTTP on its own database (`e2e`); **D** deploy drill (Docker, shell).

| # | scenario | kind | level | test |
|---|---|---|---|---|
| 1 | Items split of 2 diners + 3 equal parts of 100.00 = 33.34/33.33/33.33 | happy, money | S | `R3AcceptanceTest` (M1) |
| 2 | Random splits, 3-decimal quantities, cancels, two rates, voids: Σ gross = due, Σ tax per rate = order's − pending, Σ quantity = net | property | U + M | `SplitPropertyTest`, `SplitPreservesTotalsTest`, `SplitWithCancelsAndTwoRatesPropertyTest` |
| 3 | A quantity already partitioned → 422; two concurrent partitions → one wins | edge, concurrency | S + M | `R3AcceptanceTest`, `PartitionRaceTest` |
| 4 | Two terminals pay the same part → one Payment; same key twice → one Payment | concurrency, idempotency | M + S | `PartitionDoublePaymentTest`, `EqualPartsTest`; replay in `R3AcceptanceTest` |
| 5 | A round after a partition document lands in the residual; the issued document is unchanged | history | S | `R3AcceptanceTest` (dessert round) |
| 6 | Credit sale: reserve → document (buyer = customer) → debit; no Payment, no cash | happy | M + S | `CreditSaleTest`, `CreditIsNotCashTest`, `R3AcceptanceTest` |
| 7 | Limit 100, two charges of 60 at once → exactly one | concurrency | M | `CreditLimitRaceTest` (settlement and customers) |
| 8 | Debt payment FIFO with running balance; replay → once; more than the debt → 422 | happy, idempotency, edge | M + S | `DebtPaymentTest`, `CreditLedgerTest`, `R3AcceptanceTest` |
| 9 | Fiscal failure in a credit sale → 422, retry → one document and one debit; crash after issue → the sweeper posts once | failure | M + S | `CreditSaleFiscalFailureTest`, `CreditChargeRecoveryTest`, `RestartMatrixTest` (b) |
| 10 | Cash refund (approver) → one REFUND OUT in the refunder's session; invoiced non-overpaid → 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`; a debt payment's refund reopens the debt | happy, rule | M + S + D | `RefundTest`, `RefundRaceTest`, `R3AcceptanceTest`, real-schema drill |
| 11 | Void after partial payment within headroom → 201; beyond → 422 `VOID_EXCEEDS_UNPAID`; on invoiced quantity → 422 | rule | M + S | `SettledVoidHeadroomTest`, `VoidVersusPaymentRaceTest`, `R3AcceptanceTest` |
| 12 | D96: the cent absorbed by another line of the rate; pending when nothing can; listed until resolved; a retry places identically | money, fiscal | U + M + S | `TaxRoundingPlacementTest`, `TaxRoundingResidualTest`, `TaxCorrectionResolutionTest`, `R3AcceptanceTest` (M1, M2), `RestartMatrixTest` (g) |
| 13 | D97: "Pagamento misto — Numerário: X; A crédito: Y", "Saldo em aberto: Z", MIXED/ON_ACCOUNT, immutable, reprinted identically | fiscal | U + M + S | `InternalPrintoutPaymentsTest`, `DocumentPaymentBreakdownTest`, `PaymentSnapshotContentTest`, `R3AcceptanceTest` (B, M3) |
| 14 | The breakdown on a CP860/CP850/CP437/CP858 printer | failure | U | `PaymentBreakdownCodePageTest` (BUG-0001, fixed in ef16b3d: hyphen fallback) |
| 15 | Timeout → UNKNOWN → the poller resolves exactly one Payment; NotFound inside the window stays UNKNOWN; FAILED too early → 422 | failure | M + S | `InDoubtPollingTest`, `ReclassifyAgeGuardTest`, `R3AcceptanceTest` (M3), `RestartMatrixTest` (e) |
| 16 | Forced close of an abandoned session with a count and reason, refused while a payment is in doubt | permission, rule | M + S | `ForcedSessionCloseTest`, `R3AcceptanceTest` |
| 17 | After the cutoff with yesterday open: a session opens in the new day with `PREVIOUS_DAY_OPEN` | time | M + S | `NewDayPolicyTest`, `R3AcceptanceTest` |
| 18 | An adjustment makes an old submission stale (409 `CLOSING_STALE`); a financial reopen keeps the approved round 0; round 1 approvable | state machine | M + S | `ClosingRoundsTest`, `FinancialReopenTest`, `R3AcceptanceTest` (reopen, adjust, round 1) |
| 19 | A statement imported twice → one batch; matched, mismatched, missing as expected; no money fact changes | idempotency, rule | M + S | `StatementImportTest`, `ReconciliationNeverWritesMoneyTest`, `R3AcceptanceTest` |
| 20 | A payment after midnight and before the cutoff belongs to its business day's statement | time | M | `StatementPeriodIsBusinessDaysTest` (BUG-0002, fixed in 8c1bd64) |
| 21 | Reports equal documents, receipts and credit; `final`; rebuild from empty = live; `pendingFacts` while a listener is held | reporting | M + S | `SalesReportTest`, `ReceiptsReportTest`, `RebuildEqualsLiveTest`, `ReportFreshnessTest`, `R3AcceptanceTest` |
| 22 | 31-day report p95 < 1 s, 1-day < 300 ms on one store-year | performance | S (run by name) | `R3ReportBenchmark` |
| 23 | A waiter → 403 on the audit API; a store-A auditor never sees store B; span > 92 days → 422; export audited; nested secret rejected | permission | M + S | `AuditQueryTest`, `AuditExportTest`, `AuditLogTest`, `R3AcceptanceTest` |
| 24 | Support grant: login only under a grant, 401 and sessions revoked after expiry, every step audited | permission, time | M + S | `SupportAccessTest`, `R3AcceptanceTest` |
| 25 | Restart matrix a–h: exactly one logical effect per crash point | failure | S | `RestartMatrixTest`, `BatchSurvivesCrashTest`, `EventRepublicationTest` |
| 26 | Unrouted ticket visible, routed later prints once; subtotal twice prints twice, redelivered once; shutdown does not wait for SSE | failure | M | `UnroutedTicketTest`, `SubtotalDedupeTest`, `ChangeNotifierShutdownTest` |
| 27 | Suffix "4567" returns every match as a candidate; `EXPLAIN` uses the indexes | rule | M | `CustomerSearchTest`, `CustomerSearchPlanTest` |
| 28 | `verify()` with D65; injected forbidden edges and a second issuer fail the build | architecture | U | `ModularityTests`, `ArchitectureTests`, `ArchitectureRulesFireTest`, `SettlementIssuerRuleTest`, `ReportingReadOnlyRuleTest` |
| 29 | PITR to a chosen time in an isolated container, RPO shown | backup | D | `deploy/test-pitr.sh` (full: RPO 273 s, RTO 4 s on 2026-11-06; `--quick` does not measure the RPO) |
| 30 | Production mode: no plaintext, two recipients, no identity on the server, loose modes refused; restore with the operational key and with the recovery key alone; a wrong key fails cleanly | backup, security | D | `deploy/test-backup-encryption.sh`, `deploy/test-backup-restore.sh` |
| 31 | Backup and restore of the real R3 schema with R3 rows in every table | backup | D | `deploy/test/real-schema-drill.sh` |
| 32 | Every R3 coded error's exact client contract | contract | M | `CodedErrorContractTest` |

## Test data

Each system test runs on its own empty PostgreSQL (`Installation.FreshDatabase`) with a company bootstrapped per run, two stores (A and B), throwaway passwords and PINs, two VAT rates (16 % and 23 %) and items priced so the D96 cent appears (2 × 1.00 at 23 %). Module tests share one database and draw fresh ids and keys per test. Time moves only through `SettlementTestClock` (never a sleep): the next business day, the in-doubt poll age, the support-grant expiry. The deploy drills generate throwaway age keys outside `deploy/` and remove every container and volume they create.

## Open questions (not decided by QA)

- BUG-0001 (resolved): a printer without U+2014 prints a hyphen in the D97 breakdown.
- BUG-0002: whether MISSING_PROVIDER candidates are selected by `payment.business_date` or by the period's instants.
- §14 #6 (Internet cut keeps local operation; server/LAN cut shows "unavailable" without a false confirmation) needs the POS frontend, which does not exist yet.
- §14 #1 (installations A and B isolated: credentials, data and restore) has no automated test; the deploy design gives each company its own Compose project, volumes and secrets, but no drill installs two and crosses them.
