# 0022. Provider statements against payments

Status: Accepted 2026-11-05. Source: [r3-decisions.md](../design/r3-decisions.md) D78.

## Context

Acquirers and wallets send statements; §5 asks for "reconciliação de adquirentes". No acquirer is named yet (P10), and reconciliation must never invent money facts (§8.4 "cada efeito tem registo").

## Decision

- One `ReconciliationBatch` per imported statement (store, method, period, file SHA-256), from one canonical CSV (`occurred_at, amount, fee, reference, authorization_code, line_ref`), or fetched by an optional `StatementSource` capability of a provider adapter through `executeSteps`, with no transaction around the call (D48).
- Matching at import, in one transaction, deterministic: INTEGRATED by provider reference; EXTERNAL_TERMINAL by method + authorization code + amount; otherwise method + amount + ±10 min **only with exactly one candidate**. Outcomes: MATCHED (fee recorded), AMOUNT_MISMATCH, MISSING_INTERNAL, MISSING_PROVIDER, DUPLICATE_LINE. Payments count net of refunds.
- A resolution is a fact (`ACCEPTED`, `REFUNDED_AT_PROVIDER`, `ESCALATED`, with a reason, `RECONCILIATION_MANAGE`). **Reconciliation never creates, edits or deletes a Payment, Refund or CashMovement.**

## Consequences

- The same file imported twice is one batch: 409 `STATEMENT_ALREADY_IMPORTED` with its `batchId`.
- **Known deviation (BUG-0002, `docs/qa/bugs/0002-statement-period-ignores-business-day-cutoff.md`):** the period's instants are calendar midnights in the store's zone, not business days from the store's cutoff, so a payment made after midnight and before the cutoff falls outside its own business day's statement. Fixed in 8c1bd64: the period is `[BusinessDays.startOf(from), BusinessDays.startOf(to + 1))` (`StatementPeriodIsBusinessDaysTest`).
- Proofs: `StatementImportTest`, `StatementFetchTest`, `EntryResolutionTest`, `ReconciliationNeverWritesMoneyTest`; end to end, `R3AcceptanceTest` (one MATCHED, one MISSING_INTERNAL resolved, re-import 409, money facts unchanged).
