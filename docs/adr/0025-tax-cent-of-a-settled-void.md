# 0025. The tax cent of a settled void: absorbed in the residual, otherwise a pending correction

Status: Accepted 2026-11-06 (user decision, D96). Source: [r3-decisions.md](../design/r3-decisions.md) §18 D96. Supersedes the "settled voids" paragraph of the D68 amendment and D80's "the split's drift is an explained difference".

## Context

A settled void that empties a split line's pool leaves δ = the pool's tax, at most one minor unit: the line's tax recomputed on its net quantity minus Σ of its LIVE shares. Example: 2 × 1.00 at 23 %, one unit in partition A (share 0.18), the other voided: the line's tax is 0.19, A carries 0.18. The user: "allow the void, but reconcile the rounding difference ... keep the adjustment auditable. If a fiscal document has already been issued, preserve it and use the applicable correction process."

## Decision

- **When:** only when the residual ORDER document is drafted (close s1, `POST /orders/{id}/documents`, an ORDER-target credit sale s1), under `order_settlement`, in the drafting transaction, before `FiscalApi.createDraft`. Nothing is placed at void time.
- **Where:** each drifting line, in billing-view order, puts its δ on **one** residual draft line of the same rate: first a line sourced from itself, then any other line of that rate in draft order, the first that stays within `PartitionAmounts.withinFiscalBound`. Tax += δ, net −= δ, gross unchanged: **no money moves**.
- **Otherwise** a **pending fiscal correction** naming the document to correct (the residual, or the ISSUED partition document holding the line's newest share). Issued documents are never changed. The void and the close succeed. R3 issues no corrective document (P31).
- Facts: `settlement.tax_rounding_adjustment` (ABSORBED | PENDING_CORRECTION, `UNIQUE(order_id, source_line_id)`, append-only, audited `TAX_ROUNDING_ABSORBED` / `FISCAL_CORRECTION_PENDING`). A retried s1 re-applies the stored rows. A pending row is cleared by a `tax_correction_resolution` (`POST /backoffice/reconciliation/tax-corrections/{id}/resolutions`, `RECONCILIATION_MANAGE`, audited `TAX_CORRECTION_RESOLVED`).
- The documents control lists `TAX_CORRECTION_PENDING` per open row, an explained difference once resolved, `TAX_DRIFT_UNASSIGNED` as a bug alarm, `TAX_MISMATCH` otherwise.

## Consequences

- Σ document tax per rate = the order's tax per rate, except open pending corrections.
- Review backlog not done in S33 (production code): structured `amount`/`adjustmentId` properties on the `TAX_CORRECTION_PENDING` exception (today the amount is in `detail`).
- Proofs: `TaxRoundingPlacementTest`, `TaxRoundingResidualTest`, `TaxCorrectionResolutionTest`, `TaxRoundingAdjustmentImmutableTest`, `DocumentsControlTest`, `SplitWithCancelsAndTwoRatesPropertyTest` (both outcomes, two rates, cancels); end to end, `R3AcceptanceTest` (M1 absorbed on the 23 % MOLHO line, Σ 23 % tax 0.56 = the order's; M2 pending on its partition document until resolved), `RestartMatrixTest` (g: the same row and draft after a crash).
