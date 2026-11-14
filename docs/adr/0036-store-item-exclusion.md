# 0036. Per-store catalog availability through an explicit exclusion

Status: Accepted 2026-11-12 (R5 approval, D146–D148; P57); built in S58, integrated in S63 (2026-11-13). Source: [r5-decisions.md](../design/r5-decisions.md) §4; spec §5 ("disponibilidade"), §8.3, §14 #7. Amends the R1 release rule of [0010](0010-catalog-frozen-release.md) that every active item has a price in every store.

## Context

R1 simplified the catalog to company-wide items with one price list per store; a release requires a price for every active item (`ITEM_WITHOUT_PRICE`), although the R1 PRD allowed per-store availability. A company whose stores do not all sell the same items cannot publish. §5 lists "disponibilidade" among catalog data; §8.3 keeps item ACTIVE/INACTIVE separate from release states.

## Decision

- A working-copy record **`StoreItemExclusion(store_id, item_id)`**: no row = the item is in the store (R4 behaviour). An excluded item is left out of that store's next release and needs no price there.
- An active, non-excluded item without a price **still fails** with `ITEM_WITHOUT_PRICE`; validation also lists every excluded item and returns **warnings** (stored on the release).
- Company-wide references: a menu option excluded in a store is dropped (error if a group's minimum can no longer be met); a macro with an excluded entry and an all-stores quick-page entry or automatic item naming an excluded item are omitted with a warning; a store's own quick page or automatic item naming an excluded item is an error. Daily dishes and the delivery fee item keep their checks against the published snapshot.
- No data migration; `CatalogSnapshot` schema stays 2; with no exclusion rows a new snapshot is identical to R4's.

## Alternatives considered

- **Priced = available:** no table, but a forgotten price silently removes an item from a store's POS, and unlisting means deleting a price.
- **Opt-in listing per store:** explicit, but a data migration for every item × store and every new item must be listed in each store before it sells.

## Consequences

- Table `catalog.store_item_exclusion` and column `configuration_release.validation_warnings` (V5006); three BackOffice endpoints under `/api/v1/backoffice/catalog/stores/{storeId}/item-exclusions`.
- Three distinct availabilities: item ACTIVE/INACTIVE (company-wide), store exclusion (configuration, at the next publication), sold-out flag (operational, immediate).
- Proofs: `StoreExclusionReleaseTest`, `ExclusionReferenceRulesTest`, `NoExclusionSnapshotUnchangedTest`.
