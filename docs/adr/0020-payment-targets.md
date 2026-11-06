# 0020. Payment targets: ORDER, PARTITION, CREDIT_ACCOUNT

Status: Accepted 2026-11-05. Source: [r3-decisions.md](../design/r3-decisions.md) D69.

## Context

Split bills (§8.2) and debt payments (§5) need a payment to settle something other than a whole order, while §7.4 forbids repeating *the same* charge and the D48 flow (persist, call, record) must not be copied per case.

## Decision

`PaymentAttempt`, `Payment` and `PaymentAllocation` carry `target_type ∈ {ORDER, PARTITION, CREDIT_ACCOUNT}` with `order_id`, `partition_id` and `credit_account_id`, and a CHECK that the ids fit the type. Existing rows default to `ORDER` (metadata only; no fact is updated). The **target key** is `coalesce(partition_id, order_id, credit_account_id)`.

- Balances are per target: a partition = its due − allocations − refunds − live credit charges; the ORDER residual = order due − Σ LIVE partition dues − the same terms on the ORDER target; a customer = Σ open debits. An attempt above its target's balance is 422 `AMOUNT_EXCEEDS_BALANCE`.
- **One open attempt per target**: a doubt on partition A does not block partition B. The order close still requires no open attempt on any target of the order.
- A debt payment may allocate to several debits: allocations are `UNIQUE(payment_id, target_type, target_key)`, and a deferred constraint trigger checks Σ allocations = the payment at commit.
- Whether a payment was an advance is derived (payment instant < document `issued_at`).

## Consequences

- One payment flow for the three targets; R2 rows keep working (`R2PaymentRowsTargetTest`).
- Proofs: `PartitionDoublePaymentTest`, `AllocationSumTest`, `DebtPaymentTest`, `CreditPaymentRaceTest`; end to end, `R3AcceptanceTest` (A pays a partition by card, B part cash on its partition, equal parts paid with a key replayed once, the ORDER residual, a debt payment on the customer's account).
