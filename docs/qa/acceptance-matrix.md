# Acceptance matrix (release gate)

Owner: QA. The release gate: every §14 row `covered` and passing, no open bug of severity Critical or High, the restore test run against the release candidate, and, for fiscal use in production, the country's fiscal validation (§14 #10).

- **Branch:** `s33-integrate` (feat/backend-stage3 `6da5d81` + S33). Backend only: no frontend exists yet, so the §8.5 flows are proven over the HTTP API (`e2e`), not with Playwright.
- **Last run:** 2026-11-06, `rtk mvn clean test`: 1742 tests, 0 failures, 0 errors, 0 skipped (macOS 15.3 x86_64, 16 CPUs, Java 25.0.4.1, PostgreSQL 18.6 in Docker). Deploy drills the same day, all in production mode with throwaway keys: `test-backup-encryption.sh` PASS (63 checks, incl. the key-recovery drill), `test-backup-restore.sh` PASS, `test-pitr.sh --quick` PASS, `test-pitr.sh` (full) PASS with RPO 273 s and RTO 4 s, `test/real-schema-drill.sh` PASS (81 migrations, R3 rows in every table).
- **Statuses:** `covered` (proven and passing), `partial` (part of the criterion is proven; the rest is named), `missing` (no test), `failing` (a test proves a defect; bug linked), `n/a` (not in any release built so far).

## Release verdict (2026-11-06): **NOT READY**

- §14 #10: no certified fiscal adapter and no country fiscal validation (B4). Fiscal use in production is blocked whatever the tests say.
- §14 #1 is `missing` (no multi-installation drill) and §14 #6 `partial` (no frontend for the network-cut behaviour).
- §14 #8: the restore drills pass in production mode against this branch (RPO 273 s ≤ 15 min, RTO 4 s); the RTO is measured on drill-sized data, not on a production-sized database.
- Open bugs: BUG-0001 (Medium), BUG-0002 (Medium). None Critical or High.

## §14 — essential acceptance criteria

| # | criterion (spec §14) | status | proof | last run |
|---|---|---|---|---|
| 1 | Installations A and B with separate processes, data, volumes and secrets; A's credentials do not open B; restoring A leaves B unchanged | missing | No automated test installs two companies and crosses them. The design gives each its own Compose project, volumes and secrets (deploy/README). | — |
| 2 | Two terminals of one store see confirmed changes; concurrent confirmation loses no line and duplicates no batch | covered | `ConcurrentBatchConfirmationTest`, `SseNotificationTest`, `OrderNotificationTest`; system: `R2AcceptanceTest` (A14 change streams of store A and not B) | 2026-11-06 |
| 3 | Repeating a charge with the same key creates no second payment or movement; an external timeout stays reconcilable | covered | `IdempotencyServiceTest`, `DoublePaymentRaceTest`, `PartitionDoublePaymentTest`, `DebtPaymentTest`, `IntegratedPaymentTest`, `InDoubtPollingTest`, `ReclassifyAgeGuardTest`; system: `R2AcceptanceTest` (cash replay), `R3AcceptanceTest` (part replays, debt-payment replay, M3 UNKNOWN → one Payment by polling), `RestartMatrixTest` (b, c, e, g, h) | 2026-11-06 |
| 4 | Splitting the bill preserves total, taxes and quantities; credit never appears as money received | covered | `SplitPropertyTest`, `SplitPreservesTotalsTest`, `SplitWithCancelsAndTwoRatesPropertyTest`, `EqualPartsTest`, `TaxRoundingResidualTest`, `CreditIsNotCashTest`, `ReceiptsReportTest`; system: `R3AcceptanceTest` (Σ gross 505.00 = due, Σ 23 % tax 0.56 = the order's, Σ quantity = net; credit 150.00 reported on account, not received; no cash movement for credit). D96: an open pending correction is the one allowed difference, and it is listed. | 2026-11-06 |
| 5 | A printer off keeps the order and the pending job; recovery after a restart without logical duplication | covered | `PrintQueueTest`, `PrintWorkerTest`, `IgnoreSuppressionTest`, `UnroutedTicketTest`, `SubtotalDedupeTest`, `BatchSurvivesCrashTest`; system: `RestartMatrixTest` (a: one ticket printed once after the restart; d: a cut send is UNKNOWN, never resent) | 2026-11-06 |
| 6 | Internet cut with the local server up keeps local operation; a server/LAN cut shows "unavailable" with no false confirmation | partial | Backend half: nothing local needs the Internet; an unreachable provider leaves the attempt UNKNOWN and everything else works (`IntegratedPaymentTest`, `ProviderTimeoutGuardTest`, `R3AcceptanceTest` M3); a client retrying after "unavailable" gets exactly one effect (`RestartMatrixTest` c, g, h). Missing: the POS UI behaviour (no frontend yet). | 2026-11-06 |
| 7 | Publishing a new price leaves earlier sales and documents unchanged | covered | `BatchConfirmationHttpTest.aConfirmedLineKeepsItsPriceTaxAndNameAfterARepublicationWithNewPrices`, `OrdersSchemaTest` (confirmed lines immutable), `FiscalImmutabilityTest`; `R3AcceptanceTest` (A's document unchanged after a later round; M2's partition document unchanged after the close) | 2026-11-06 |
| 8 | Restoring a backup in an isolated environment demonstrates the agreed recovery objectives (RPO 15 min, RTO 2 h) | covered | `deploy/test-pitr.sh` (full: restore to a chosen time in an isolated container; RPO measured 273 s, RTO 4 s), `deploy/test-backup-restore.sh`, `deploy/test-backup-encryption.sh` (incl. `verify-restore.sh --key-recovery` with the recovery key alone), `deploy/test/real-schema-drill.sh` (the R3 schema with rows in every table): all PASS on this branch in production mode. Caveat: the RTO is measured on drill-sized data. | 2026-11-06 |
| 9 | An operator without permission cannot change fiscal data, prices or closings through the API | covered | `SettlementAccessHttpTest`, `StoreFirstAuthorizationHttpTest`, `StoreScopeHttpTest`, `ReleaseHttpTest`, `FinancialReopenTest`, `ForcedSessionCloseTest.withoutThePermissionItIsForbidden`, `ReportAccessHttpTest`, `AuditQueryTest.aWaiterIsForbidden`; system: `R2AcceptanceTest` (BackOffice session paying at the POS 403), `R3AcceptanceTest` (refund without approver 403, waiter 403 and store-A auditor 403 on store B) | 2026-11-06 |
| 10 | Tests and the country's fiscal validation before invoicing in production | missing | Blocked by B4: only the internal non-certified adapter exists (`FiscalStartupGuardTest` keeps it from starting without `allow-non-certified`). | — |

## §7 — consistency and failure handling

| rule | status | proof | last run |
|---|---|---|---|
| 7.1 Confirming validates the version and writes lines, batch and production work in one transaction | covered | `BatchConfirmationHttpTest`, `ConcurrentBatchConfirmationTest`, `BatchSurvivesCrashTest` | 2026-11-06 |
| 7.2 The worker runs only confirmed work; failures have retry, a limit, an alert and intervention | covered | `PrintWorkerTest`, `PrintQueueTest`, `ProductionMetricsTest`, `PrintJobHttpTest` | 2026-11-06 |
| 7.3 Persist the attempt and reference before calling the provider; no DB lock during the call | covered | `IntegratedPaymentTest`, `ProviderTimeoutGuardTest`, `ExternalCallsTest`, `StatementFetchTest` (no transaction around the statement call) | 2026-11-06 |
| 7.4 A timeout makes the attempt UNKNOWN and a reconciliation pendency; query the provider before repeating | covered | `InDoubtPollingTest`, `ReclassifyAgeGuardTest`, `InDoubtAttemptsTest`, `SessionCloseBlockedByUnknownTest`; system: `R3AcceptanceTest` (M3), `RestartMatrixTest` (e) | 2026-11-06 |
| 7.5 Authenticated, idempotent confirmation records the receipt and the cash movement once | covered | `DoublePaymentRaceTest`, `CashPaymentTest`, `ExternalTerminalPaymentTest`; `R2AcceptanceTest` | 2026-11-06 |
| 7.6 Fiscal issue is idempotent with its own numbering; a fiscal failure after payment is a visible pendency with no new charge | covered | `GaplessNumberingTest`, `CreditSaleFiscalFailureTest`, `OrderCloseResumeTest`, `PartitionDocumentTest`; system: `RestartMatrixTest` (c, g, h: one number each) | 2026-11-06 |
| 7.7 Credit and a document issued without receipt create no cash-in movement | covered | `CreditIsNotCashTest`, `CreditSaleTest`; `R3AcceptanceTest` (B's credit: no movement, no Payment), `RestartMatrixTest` (b) | 2026-11-06 |
| 7.8 Closing validates balances, pending operations, permissions and session state in a transaction | covered | `OrderCloseTest`, `DayCloseBlockedByOpenOrderTest`, `SessionCloseBlockedByUnknownTest`, `ForcedSessionCloseTest`, `ClosingRoundsTest` (`CLOSING_STALE`); `R3AcceptanceTest` (forced close 409 `PAYMENT_IN_DOUBT`) | 2026-11-06 |
| Events: persisted publications, recovery after a crash, idempotent consumers | covered | `EventRepublicationTest`, `EventPublicationRecoveryTest` (deterministic since S33), `EventPublicationStatsTest`; `RestartMatrixTest` (a, f) | 2026-11-06 |

## §8.4 — business rules (the critical ones)

| rule | status | proof | last run |
|---|---|---|---|
| Price and tax fixed at confirmation; later changes only by an explicit, audited operation | covered | `BatchConfirmationHttpTest`, `OrdersSchemaTest`, `VoidHttpTest`, `SettledVoidApiTest` | 2026-11-06 |
| Promotions: deterministic, never above the eligible base, never a negative total | n/a | Not built (P35: R4) | — |
| An offer still consumes ingredients; own consumption is its own modality | n/a | Not built (stock: later release) | — |
| Void, attempt cancel, refund and fiscal correction are separate operations, each recorded and authorized | partial | Void, cancel and refund: `VoidHttpTest`, `PaymentAttemptTest`, `RefundTest`, `SettledVoidHeadroomTest`, `R3AcceptanceTest`. Fiscal correction: refused 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER` until the market adapter (P31); D96 records a pending correction instead. | 2026-11-06 |
| A reprint creates no new invoice, receipt, points or stock movement | covered | `ReprintHttpTest`, `FiscalImmutabilityTest`; `R3AcceptanceTest` (M3 reprint: same snapshot block, marked CÓPIA) | 2026-11-06 |
| Loyalty points on the settled eligible base; reversals are new movements | n/a | Not built | — |
| Recipes consume once; a financial reversal does not restore stock; waste recorded even when given away | n/a | Not built (stock) | — |
| Cash closing covers every session of the register; no session closes over an unknown charge; no day closes with pending sessions or orders | covered | `SessionCloseBlockedByUnknownTest`, `DayCloseBlockedByOpenOrderTest`, `ForcedSessionCloseTest`; `R2AcceptanceTest` (A11), `R3AcceptanceTest` | 2026-11-06 |
| A credit debit validates and reserves the limit in a transaction (two terminals cannot pass it) | covered | `customers.CreditLimitRaceTest`, `settlement.CreditLimitRaceTest`, `CreditChargeCancelRaceTest` | 2026-11-06 |
| One base currency; no mixing without a conversion rule | covered | `MoneyTest` (currency mismatch refused), the company currency from bootstrap | 2026-11-06 |

## R3 acceptance criteria (r3-decisions §16)

| # | criterion (short) | status | proof (module level → system level) | last run |
|---|---|---|---|---|
| A16 | Items split, residual, Σ gross = due, Σ tax per rate, Σ quantity per line (3 decimals) | covered | `SplitPropertyTest`, `SplitPreservesTotalsTest`, `SplitWithCancelsAndTwoRatesPropertyTest`, `ResidualDocumentCloseTest` → `R3AcceptanceTest` M1 | 2026-11-06 |
| A17 | 100.00 / 3 = 33.34 / 33.33 / 33.33; same part from two terminals → one; same key → one | covered | `EqualPartsTest`, `PartitionDoublePaymentTest`, `PartitionAmountsTest` → `R3AcceptanceTest` M1 | 2026-11-06 |
| A18 | Quantity already in a LIVE partition 422; concurrent partitions → one; a paid partition cannot be cancelled | covered | `PartitionTest`, `PartitionRaceTest` → `R3AcceptanceTest` (422 `QUANTITY_ALREADY_PARTITIONED`) | 2026-11-06 |
| A19 | A new round after a partition document goes to the residual; the document is unchanged | covered | `PartitionDocumentTest` → `R3AcceptanceTest` (dessert round) | 2026-11-06 |
| A20 | Credit sale: reserve → document → debit, no cash, no Payment, SETTLED, on account in reports; limit race → one | covered | `CreditSaleTest`, `CreditIsNotCashTest`, `CreditLimitRaceTest` ×2, `ReceiptsReportTest` → `R3AcceptanceTest` (B, M3; on account 150.00 not received) | 2026-11-06 |
| A21 | Debt payment: one receipt, FIFO, running balance; replay once; above the debt 422 | covered | `DebtPaymentTest`, `CreditLedgerTest`, `CreditPaymentRaceTest`, `AllocationSumTest` → `R3AcceptanceTest` (50.00 → 150.00 → 30.00, replay, 422 `AMOUNT_EXCEEDS_DEBT`, slip printed) | 2026-11-06 |
| A22 | Fiscal failure in a credit sale 422, retry → one document, one debit; crash after issue → one debit by the sweeper | covered | `CreditSaleFiscalFailureTest`, `CreditChargeRecoveryTest` → `RestartMatrixTest` (b) | 2026-11-06 |
| A23 | Cash refund: one REFUND OUT in the refunder's session, approver audited; invoiced non-overpaid 422; a debt payment's refund reopens the debt | covered | `RefundTest`, `RefundRaceTest`, `RefundMovementTest` → `R3AcceptanceTest` (refund 1.00, then 422 `CORRECTION_REQUIRES_FISCAL_ADAPTER`), real-schema drill (debt-payment refund) | 2026-11-06 |
| A24 | Void after partial payment within headroom, with a void ticket; beyond 422; on invoiced quantity 422 | covered | `SettledVoidHeadroomTest`, `VoidVersusPaymentRaceTest`, `SettledVoidApiTest` → `R3AcceptanceTest` (422, refund, 201 + ANULAÇÃO printed, 422 on PREGO) | 2026-11-06 |
| A25 | UNKNOWN → polling → one Payment; NotFound in the window stays UNKNOWN; FAILED too early 422; a statement imported twice → one batch; no money fact changes | covered | `InDoubtPollingTest`, `ReclassifyAgeGuardTest`, `StatementImportTest`, `ReconciliationNeverWritesMoneyTest` → `R3AcceptanceTest` (M3, statement), `RestartMatrixTest` (e). See BUG-0002 for the period's business days. | 2026-11-06 |
| A26 | Adjustment → `CLOSING_STALE`; reopen keeps round 0, round 1 approvable; forced close with count and reason, not while in doubt; P28 warning | covered | `ClosingRoundsTest`, `FinancialReopenTest`, `ForcedSessionCloseTest`, `NewDayPolicyTest`, `ForcedCloseCashTest` → `R3AcceptanceTest` (forced close 409 then 200 audited; PREVIOUS_DAY_OPEN; reopen, adjust, round 1 approved) | 2026-11-06 |
| A27 | Waiter 403; store-A auditor never sees store B; span > 92 days 422; export audited; nested secret rejected | covered | `AuditQueryTest`, `AuditExportTest`, `AuditExportCapTest`, `AuditLogTest`, `CodedErrorContractTest` (RANGE_TOO_LARGE, EXPORT_TOO_LARGE) → `R3AcceptanceTest` | 2026-11-06 |
| A28 | Reports match the documents, taxes, receipts; credit separate; rebuild = live; `pendingFacts` while held; `final` | covered | `SalesReportTest`, `ReceiptsReportTest`, `ReportByBusinessDayTest`, `RebuildEqualsLiveTest`, `ReportFreshnessTest` → `R3AcceptanceTest`; D86 speed: `R3ReportBenchmark` (all targets met) | 2026-11-06 |
| A29 | Restart matrix a–f (+ g, h): one logical effect each; a listener failing once is completed without a restart | covered | `BatchSurvivesCrashTest`, `EventRepublicationTest`, `EventPublicationRecoveryTest`, `CreditChargeRecoveryTest`, `OrderCloseResumeTest`, `TaxRoundingResidualTest` → `RestartMatrixTest` (a–h, a second instance on the same database) | 2026-11-06 |
| A30 | Unrouted ticket visible, routed later prints once; subtotal twice prints twice, redelivered once; shutdown does not wait for SSE | covered | `UnroutedTicketTest`, `SubtotalDedupeTest`, `ChangeNotifierShutdownTest`, `CodedErrorContractTest` (STILL_UNROUTED, TICKET_ALREADY_ROUTED) | 2026-11-06 |
| A31 | Suffix "4567": every match as a candidate, even one; `EXPLAIN` uses the indexes | covered | `CustomerSearchTest`, `CustomerSearchPlanTest`, `CustomerQueryTest` | 2026-11-06 |
| A32 | `verify()` with D65; injected forbidden edges fail; `SettlementIssuer` the only issuer | covered | `ModularityTests`, `ArchitectureTests`, `ArchitectureRulesFireTest` (incl. the S33 widened issuer rule), `SettlementIssuerRuleTest`, `ReportingReadOnlyRuleTest` | 2026-11-06 |
| A33 | PITR to a chosen time in an isolated container; data as of that time; RPO shown | covered | `deploy/test-pitr.sh` (full) PASS: W1/W2 present and W3 absent at T, every table equal to its fingerprint at T, RPO 273 s (bound `archive_timeout` 300 s), RTO 4 s; `--quick` PASS too | 2026-11-06 |
| A34 | Support grant 2 h: login works; after expiry 401 and sessions revoked; every step audited | covered | `identity.SupportAccessTest`, `identity.service.SupportAccessTest`, `SupportGrantTest`, `CodedErrorContractTest` (SUPPORT_*) → `R3AcceptanceTest` | 2026-11-06 |
| A35 | D96: absorbed when possible, else PENDING_CORRECTION listed until resolved; a retried close gives the same rows and draft | covered | `TaxRoundingPlacementTest`, `TaxRoundingResidualTest`, `TaxCorrectionResolutionTest`, `DocumentsControlTest`, `SplitWithCancelsAndTwoRatesPropertyTest` → `R3AcceptanceTest` (M1 absorbed, M2 pending → resolved), `RestartMatrixTest` (g), real-schema drill | 2026-11-06 |
| A36 | D97: `Pagamento misto — Numerário: 20.00; A crédito: 30.00`, MIXED, on account, no whole "A CRÉDITO"; credit first + `Saldo em aberto`; full credit "A CRÉDITO"; immutable; reprint identical; live `paymentStatus` | covered on WPC1252 / failing on CP860 | `InternalPrintoutPaymentsTest`, `DocumentPaymentBreakdownTest`, `PaymentSnapshotContentTest` → `R3AcceptanceTest` (WPC1252 printers), `RestartMatrixTest` (h). **BUG-0001**: on CP437/CP850/CP860/CP858 the dash prints `?` (`PaymentBreakdownCodePageRepro`, expected failure). | 2026-11-06 |
| A37 | D98: production refuses without two recipients, no plaintext anywhere, identity on the server or loose modes refused; restore with the operational and with the recovery key alone; wrong key fails cleanly | covered | `deploy/test-backup-encryption.sh`, `deploy/test-backup-restore.sh`, `deploy/test-pitr.sh --quick`, `deploy/test/real-schema-drill.sh` (all production mode, throwaway keys) | 2026-11-06 |

## Open bugs

| id | severity | breaks | owner | reproducing test |
|---|---|---|---|---|
| [0001](bugs/0001-payment-breakdown-dash-prints-as-question-mark.md) | Medium | D97 rule 6 (exact breakdown text) on CP437/CP850/CP860/CP858 | developer (+ open question) | `PaymentBreakdownCodePageRepro` |
| [0002](bugs/0002-statement-period-ignores-business-day-cutoff.md) | Medium | D78 / P37: a statement period is business days | developer | `StatementPeriodIsBusinessDaysRepro` |
