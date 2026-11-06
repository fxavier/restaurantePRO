# 0021. Credit account, reservation and ledger in `customers`

Status: Accepted 2026-11-05. Source: [r3-decisions.md](../design/r3-decisions.md) D73.

## Context

A sale on account ("venda a crédito", §5, §7.7) must never put a customer over the limit, even from two terminals at once (§8.4), and the balance is a projection, never a stored field (§5 "saldo é uma projeção").

## Decision

- **Balance = Σ DEBIT − Σ CREDIT** over `credit_ledger_entry`, never stored. **Available = limit − balance − Σ ACTIVE reservations.**
- `CustomersApi.reserve(customerId, amount, chargeId)` takes `credit_account FOR UPDATE`, re-checks active, not blocked and available ≥ amount, then inserts the reservation. Posting the debit consumes the reservation in the same transaction.
- A debt payment posts a CREDIT and its `CreditSettlement` rows, FIFO over open debits by `occurred_at` (or the debits the client names). It is at most the open debt (P33: no advance).
- Limit changes are facts with a reason and an actor (`POST /credit-limit-changes`). No endpoint sets a balance (§9).

## Consequences

- The lock is the defence against the double-limit race; facts are append-only (`CreditFactsImmutableTest`).
- A credit sale creates no Payment and no cash movement (§14 #4: credit never appears as cash received).
- Proofs: `CreditLimitRaceTest` (limit 100, two charges of 60 → one), `CreditLedgerTest`, `CreditIsNotCashTest`, `CreditChargeRecoveryTest`; end to end, `R3AcceptanceTest` (B's 50.00 and M3's 100.00 on account, then 120.00 paid FIFO with a running balance 50.00 → 150.00 → 30.00, a replay paid once, 30.01 refused `AMOUNT_EXCEEDS_DEBT`) and `RestartMatrixTest` (b).
