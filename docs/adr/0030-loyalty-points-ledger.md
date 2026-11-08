# 0030. Loyalty: a points ledger, never a stored balance

Status: Accepted 2026-11-06 (R4 approval). Source: [r4-decisions.md](../design/r4-decisions.md) D110, with D111, D112, the D112a amendment (S46 review) and D132.

## Context

§5 asks for loyalty points "incluindo reversões", §8.1 puts the ledger in `promotions`, and §8.4 requires manual adjustments to be explicit and audited. Customers are company-wide (P38), so a member earns and redeems in any store. Two terminals may redeem the customer's last points at once.

## Decision

- `LoyaltyAccount` (one per customer, company-wide, `ACTIVE` or `SUSPENDED`) and the `LoyaltyPointLedger` (its entries are `LoyaltyPointEntry`, table `promotions.loyalty_point_entry`) live in `promotions`. Points are whole numbers. **Balance = Σ entries**, never stored. Enrollment is explicit (`POST /api/v1/pos/customers/{id}/loyalty`), never implied by a sale.
- Debits (REDEEM, EXPIRY, negative ADJUSTMENT) are allocated to earnings FIFO by `occurred_at` through `LoyaltyAllocation`, so an expiry rule needs only the remaining points per earning.
- Every reversal is a new entry referencing the original (`UNIQUE (reverses_entry_id)`); one entry per source and kind (`UNIQUE (source_type, source_id, kind)`). Manual adjustments need `LOYALTY_ADJUST` and a reason.
- **Earning** (D111) happens in `OrdersApi.close`, in settlement's close transaction, under the program version PUBLISHED at the close instant: points = ⌊Σ eligible effective gross ÷ spendUnit⌋ × pointsPerUnit; rewarded lines (and copies of them) are not eligible. `UNIQUE (ORDER, order_id, EARN)`, so a resumed close earns once. Earning takes no `FOR UPDATE`.
- **Redemption** (D112) is a reward on one unit through `POST /pos/orders/{id}/discounts {kind: REWARD}`: `loyalty_account FOR UPDATE` → ACTIVE → balance ≥ cost → REDEEM entry and its allocations → the new unit price, in the orders transaction.
- **Rewarded lineage** (D112a): a rewarded line is transferred and merged like any line. The redemption is reversed exactly once, by the void that leaves the reward's lineage with net quantity 0, through `orders.service.RewardLineage`; the points go back to the customer on the REDEEM entry. That void locks the reward-holding `order_line` `FOR NO KEY UPDATE` right after its own order's version claim.
- Lock order (D132): `order_settlement` → `order_header` → reward-holding `order_line` (voids only) → `credit_account` → `loyalty_account` → cash → fiscal.

## Rejected

- A `points` column on the account: a second source of truth (as D73 for credit, [ADR 0021](0021-credit-account-reservation-and-ledger.md)).
- Loyalty in `customers`: §8.1 puts it in `promotions`.
- Earning from a settlement or fiscal event: asynchronous, and promotions would need an edge back into orders (D99, [ADR 0028](0028-r4-module-dependencies.md)).

## Consequences

- A reprint, or a refund of an overpayment, changes no points (§8.4). Changing an order's customer after a redemption is refused (422 `LOYALTY_REDEEMED`).
- Proofs: `LoyaltyNoBalanceColumnTest`, `LoyaltyLedgerAllocationTest`, `LoyaltyRedeemRaceTest`, `LoyaltyEarnIdempotencyTest`, `LoyaltyEarnRuleTest`, `LoyaltyRedemptionTest`, `LoyaltyBalancePlanTest` (promotions); `RewardRedemptionTest`, `LoyaltyEarnAtCloseTest` (orders).
