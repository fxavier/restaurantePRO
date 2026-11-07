# 0031. Stock: a movement ledger, a balance projection and a weighted moving average

Status: Accepted 2026-11-06 (R4 approval, P40), amended 2026-11-07 (S47 review rounds 3–4). Source: [r4-decisions.md](../design/r4-decisions.md) D113 and its amendment, with D117 (amended), D120, D125 (amended) and D132.

## Context

§5: "Movimentos são o registo de referência"; §8.6 asks for an average cost ("custo médio"); §5 asks that the balance be reconcilable. Sales consume concurrently on the same items, transfers and counts post several items at once, and margins (D131) need a value per movement that never drifts.

## Decision

- **`StockMovement` is the record of reference**: signed `quantity` (base unit, `NUMERIC(19,6)`), `unit_cost` (`NUMERIC(19,6)`), signed `value` (`NUMERIC(19,4)`), currency, source, recipe version, `business_date`, `occurred_at` (the economic instant), `posted_at` and `posting_seq`. Movements are immutable.
- **`StockBalance(warehouse, stock item)`** holds quantity, value and average cost, updated **in the posting transaction under `FOR UPDATE`**, in ascending `(warehouse_id, stock_item_id)` order. `inventory.service.StockLedger` is the one posting path; a caller that posts more than once in a transaction first calls `StockLedger.lock` for every key it will touch.
- **Valuation** (HALF_UP; scale 6 for unit costs, 4 for values):
  - An IN with its own cost is valued at `round(q × cost, 4)`; an IN without a cost at the average.
  - An OUT from a balance with `Q > 0` and `V ≥ 0` that leaves the quantity at or above zero takes its **proportional share**, `−round(V × q ÷ Q, 4)`; one that empties the stock (`q = Q`) takes exactly `−V`. The OUT still records the average as its unit cost, for reference. So the value never goes below zero while the quantity stays at or above zero, and there is no drift bias.
  - An OUT crossing or below zero takes `−round(q × avg, 4)`. When an IN brings a negative quantity back to zero or above, a `REVALUATION` (quantity 0) sets `value = quantity × new cost`.
  - **A `TRANSFER_IN` carries exactly its `TRANSFER_OUT`'s posted value**, so a transfer never creates or destroys value.
  - **An explicit value is accepted only on a `CONSUMPTION_REVERSAL` or a `TRANSFER_IN`** (at its own cost, value ≥ 0), besides a `REVALUATION`'s own value (`PostMovement` refuses any other). A void's reversal is at most what is left of the sale's value, and the share that settles the sale's last quantity carries exactly what is left. No code posts a value from `q × unitCost` when a posted value exists.
- A void with waste posts no movement; its cost share is reclassified from sale to waste in `inventory.waste_record` (D117 amendment). A cost correction revalues only its receipt line's share still in stock and books the rest as a `CostVariance` (D125 amendment); a positive `cost_variance.value` means more cost of goods.
- **The balance is a reconcilable projection**: Σ movements = balance in quantity and value, checked nightly by `StockBalanceReconciliationJob` (`restaurantpro.inventory.reconcile-cron`, alert `inventory_balance_drift`), and `POST /api/v1/backoffice/inventory/balances/rebuild` replays it in `posting_seq` order.
- Locks (D132): inventory never locks `stock_balance` in the same transaction as the orders, settlement, customers, promotions, cash or fiscal locks. Within inventory, the order-line advisory locks of consumption (`ConsumptionBook.lockLines`, `pg_advisory_xact_lock`, in id order) come before any `stock_balance` lock.

## Rejected

- No balance row, average computed on read: the moving average depends on posting order, so every read would replay the item's history.
- A periodic (monthly) average: margins would be unknown until period end.
- FIFO layers: P40.
- The round-3 clamp of an OUT's value (replaced by the proportional share in round 4).

## Consequences

- Margins (D131) and stock valuation read the same posted values, and a rebuild reproduces them exactly.
- Proofs: `MovingAverageTest`, `StockLedgerTest`, `PostMovementTest`, `TransferValueTest`, `VoidReversalTest`, `AverageCostConcurrencyTest`, `StockPostingDeadlockTest`, `TransferDeadlockTest`, `SaleAndTransferLockTest`, `StockBalanceReconcileTest`, `StockMovementImmutableTest`, `CostCorrectionShareTest`, `SupplierReturnCostTest`.
