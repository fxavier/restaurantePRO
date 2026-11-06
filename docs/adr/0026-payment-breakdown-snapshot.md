# 0026. The payment breakdown on the document, a snapshot at issue

Status: Accepted 2026-11-06 (user decision, D97). Source: [r3-decisions.md](../design/r3-decisions.md) §18 D97. Supersedes S22's whole-document "A CRÉDITO" on an on-account document and D74's reading of it.

## Context

The user: "Do not label the whole invoice exclusively 'A CRÉDITO'. Show the breakdown: 'Pagamento misto — Numerário: X; A crédito: Y', including the outstanding balance. Payment status should reflect partial payment." An issued document is immutable (§8.2), so it can state only the position at issue.

## Decision

- `PaymentSnapshot(status, methods[], credit, outstanding)`: methods are Σ payments − refunds on the targets the document covers (a partition document: its partition; the residual: the ORDER target plus every LIVE partition without its own document), in first-confirmation order, labelled with the method's name; credit = Σ live charges; outstanding = gross − paid − credit (negative is refused as a bug).
- `PaymentStatus.of(G, P, C)`: UNPAID, PARTIALLY_PAID, PAID, ON_ACCOUNT (C = G) or MIXED (0 < C < G). The same function gives the live `paymentStatus` of the settlement and partition views.
- `settlement.service.SettlementIssuer.issue` is the single settlement path to `FiscalApi.issue`: a short transaction under `order_settlement` computes the snapshot and calls `FiscalApi.recordPayments` (MANDATORY), then issues with no transaction. A DRAFT without a snapshot is refused (`FISCAL_PENDING`, `PAYMENT_SNAPSHOT_MISSING`) and the retry refreshes it. The snapshot is immutable after issue; a reprint prints the same block.
- The internal adapter prints, after `TOTAL`, `Pagamento — …`, `Pagamento misto — …; A crédito: Y` (credit last), `Pagamento parcial — …`, and `Saldo em aberto: Z` when Z > 0; the header "A CRÉDITO" only for ON_ACCOUNT. `on_account = (credit_total > 0)` once a snapshot exists.

## Consequences

- An ArchUnit rule (widened in S33 to every module and to `recordPayments`) keeps `SettlementIssuer` the only caller.
- **Known deviation (BUG-0001, `docs/qa/bugs/0001-payment-breakdown-dash-prints-as-question-mark.md`):** the em dash exists only in the WPC1252 printer table; on CP437, CP850, CP860 (the Portuguese default) and CP858 the paper reads `Pagamento misto ? Numerário: …`. Fixed in ef16b3d: a table without the dash prints the ASCII hyphen (`Pagamento misto - Numerário: …`), never `?`; WPC1252 keeps the exact text (`PaymentBreakdownCodePageTest`).
- Review backlog done in S33b (b3b6fe0): a negative computed outstanding is logged at ERROR with the order and document ids, and refused.
- Proofs: `DocumentPaymentBreakdownTest`, `PaymentSnapshotContentTest`, `InternalPrintoutPaymentsTest`, `SettlementIssuerRuleTest`; end to end, `R3AcceptanceTest` (B: `Pagamento misto — Numerário: 100.00; A crédito: 50.00`, MIXED, on account, no "A CRÉDITO"; M3: `Pagamento misto — A crédito: 100.00` + `Saldo em aberto: 50.00`, unchanged after the later payment and reprinted identically), `RestartMatrixTest` (h: a fresh snapshot after a crash between the refresh and the issue).
