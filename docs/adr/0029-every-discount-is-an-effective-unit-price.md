# 0029. Every discount is an effective unit price

Status: Accepted 2026-11-06 (R4 approval). Source: [r4-decisions.md](../design/r4-decisions.md) D101, with D103–D107 and the D107 amendment (S53 review, 2026-11-07) and D112a.

## Context

Promotions, manual discounts, offers and loyalty rewards (§4, §5, §8.4) all lower what a line costs the customer. The R1–R3 money rules already depend on one per-line amount rule (`LineAmounts.of`, D59): partitions (D68), the tax cent of a settled void (D96, [ADR 0025](0025-tax-cent-of-a-settled-void.md)), fiscal's line check and the documents. §8.4 forbids negative totals, fiscal lines have `quantity > 0` and non-negative amounts, and §8.4 asks that each discount keeps its origin ("guardar origem").

## Decision

- A promotion, a manual discount, an offer and a reward all produce one thing: the line's **effective unit price**, in minor units (D15), with `0 ≤ effective ≤ list` (CHECK `order_line_effective_within_list_ck`). `LineAmounts.of(effective, netQty, rate)` stays the only amount rule.
- Percent off is `round_HALF_UP(price × (1 − p/100))` **per unit** (10 % off 1.25 is 1.13). An offer is 0.00. A bill-wide percent is the same percent on each chosen line.
- The discount amount is `LineAmounts.of(list, q, rate).gross − LineAmounts.of(effective, q, rate).gross`, derived, never stored as a money column.
- Promotions price a line at **confirmation** (D103: one promotion per line, by priority, then lowest price, then code; windows in the store's zone at the confirmation instant), with a visible recalculation (D104: 409 `PROMOTIONS_CHANGED` and `PRICES_CHANGED`). After confirmation a discount is a fact, an `OrderLinePriceAdjustment` (`DISCOUNT`, `OFFER`, `REWARD`, `REVERSAL`), and a part of a line is first split off (`OrderLineSplit`, D105). The effective price is the latest adjustment's, else the line's snapshot.
- Limits (D106) are measured cumulatively against the list price. The tax follows the discounted gross (D107); the document line carries `listUnitPrice` and `discountLabel` for printing.
- **Origin and label of a copy** (D107 amendment): a split or transferred copy without an adjustment of its own takes the origin and label of the latest non-reversal adjustment in its lineage **only when its effective price equals that adjustment's price**; otherwise the promotion's name, else MANUAL "Desconto". `DiscountLine.reversesOrigin` uses the same lineage walk, bounded by the reversal's instant. Only an OFFER-origin line at unit price 0 counts as an offer (`OrdersApi.offerFacts`, cost of offers).
- **Rewards** (D112a): "does this line carry a reward" is answered only by `orders.service.RewardLineage` (ancestry through transfers and splits after the reward), never from a line's own adjustment history. A rewarded lineage refuses a second reward (422 `REWARD_ALREADY_REDEEMED`) and a REVERSAL.

## Rejected

- A discount amount on the line, subtracted from the gross: it would have to shrink with voids and be split by D68, with cross-line terms and a new fiscal bound.
- Negative discount lines: forbidden by §8.4 and regime-dependent in fiscal.
- Bill-level discounts allocated across lines: allocated cents do not divide by quantity, so the unit price would not be exact.

## Consequences

- Partitions, the D96 tax cent, fiscal checks and the documents are unchanged; a regime that taxes offers at full value, or wants a separate discount line, is handled by its market adapter from `listUnitPrice` and the label (B4 assumption).
- Pending DRAFT documents drafted before S51 for discounted lines carry no list price or label; discard them before an upgrade (D107 upgrade note).
- Proofs: `EffectivePricePropertyTest`, `LineSplitTest`, `DiscountLimitTest`, `DiscountConcurrencyTest`, `OfferLineageTest`, `RewardRedemptionTest`, `ConfirmWithPromotionTest`, `HappyHourBoundaryTest`, `PromotionEngineTest`, `DiscountedDraftLineTest` (fiscal), `SettledDiscountHeadroomTest` and `SplitPreservesTotalsTest` (settlement), `DiscountReportTest` (reporting).
