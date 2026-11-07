# 0032. Consumption: an event-triggered, idempotent pull from the confirmed round

Status: Accepted 2026-11-06 (R4 approval, P42). Source: [r4-decisions.md](../design/r4-decisions.md) D116, with D115, D117 (amended), D119a, D122 (amended) and D132. Pattern: [ADR 0023](0023-reporting-projections-by-event-triggered-pulls.md).

## Context

§5 requires that stock processing never blocks a sale, and that consumption follows the recipe version in force when the round was confirmed (§8 "snapshots de… receita"). §8.2: transferred lines must never be consumed twice. §8.4: a financial reversal (refund, credit note) is not a stock fact. `OrderBatchConfirmed` already exists and is persisted by the publication registry (D19, [ADR 0004](0004-event-publication-registry.md)).

## Decision

- Inventory listens to `OrderBatchConfirmed` and **pulls** `OrdersApi.batchFacts(batchId)` (readOnly: mode, business day, confirmation instant, and per line item, variant, release, quantity, destination and menu components), as reporting does (D84).
- In one transaction it takes the order-line advisory locks (`ConsumptionBook.lockLines`), then resolves per line or component the family's consumption rule (`ConsumptionPolicyVersion`, effective at confirmation), the recipe version effective at confirmation (D115, packaging per mode), and the warehouse from the `ConsumptionRoute`, and posts `SALE_CONSUMPTION` through `StockLedger` with source `ORDER_LINE` (or `ORDER_LINE_COMPONENT`) and `INSERT … ON CONFLICT DO NOTHING` on the source key. A redelivered event posts nothing new; a replay with other values is a 409 `MOVEMENT_SOURCE_MISMATCH`.
- Voids: inventory listens to `OrderLinesVoided` and pulls `VoidFacts` (with `consumedLineId`, the batch line a void descends from). Without waste: a `CONSUMPTION_REVERSAL` at the sale's value share. With waste: no movement, a `waste_record` reclassification (D117 amendment).
- **A delay never blocks a sale**: the order transaction never touches inventory; the backlog is `pendingFacts` (`EventPublicationStats`) on `GET /api/v1/backoffice/inventory/status`. A count's approval is refused while a consumption publication of its store for a round at or before the cut-off is pending (409 `CONSUMPTION_BACKLOG`, D119a).
- Lines with no recipe, no route or an inactive stock item become a `ConsumptionException` (visible, `reprocess` after the fix), never a silent skip. Only lines confirmed at or after the store's activation are consumed (D122); history is never backfilled.

## Rejected

- A batch job scanning confirmed lines: a second mechanism next to the registry, with its own cursor.
- Consuming from settlement or fiscal events: a financial reversal would then look like a stock fact (§8.4).
- Consuming at batch DISPATCHED: it only means production received the work.
- Orders calling inventory at confirmation: `orders ↛ inventory` (D99, [ADR 0028](0028-r4-module-dependencies.md)).

## Consequences

- Transferred and split lines have no batch, so they are never consumed again; a void of a transferred line reverses the original batch line's consumption.
- After a crash between a round's commit and its consumption, the registry redelivers and exactly one set of movements is posted (restart matrix case (i), S54).
- Proofs: `ConsumptionFlowTest`, `ConsumptionIdempotencyTest`, `ConsumptionRestartTest`, `SaleNotBlockedByInventoryTest`, `RecipeVersionAtConfirmationTest`, `PackagingByModeTest`, `ConsumptionRouteTest`, `ConsumptionPolicyTrackingTest`, `ConsumptionExceptionTest`, `TransferredLineVoidConsumptionTest`, `VoidReversalTest`, `GoLiveCutoffTest`, `CountBacklogTest`.
