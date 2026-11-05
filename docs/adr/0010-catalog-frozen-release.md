# 0010. Catalog working copy plus a frozen published release

Status: Accepted, 2026-11-04 (D31; read API per S9, tax schedule per D58). Source: [backend-decisions.md](../design/backend-decisions.md) D31, [r2-decisions.md](../design/r2-decisions.md) D58; spec §5, §8.3, §14 #7.

## Context

Spec §14 #7: republishing the catalog must never change past sales. POS terminals need one consistent catalog version.

## Decision

Administrators edit a normal relational working copy (families, items, variants, units, barcodes, tax categories and rates, price lists, production destinations). A `ConfigurationRelease` goes DRAFT → VALIDATED → PUBLISHED → RETIRED. Publishing freezes a complete per-store snapshot as `jsonb`, with version, effective time and author; the previous release becomes RETIRED. The POS reads only the current published snapshot and its version. Orders copy description, price and tax onto the line at confirmation. Item availability (ACTIVE/INACTIVE) is separate from release state.

Rejected: versioning every row (SCD-2): more tables and harder queries for the same guarantee.

## Consequences

- R2 (S9): `CatalogApi.currentSnapshot(storeId)` and `snapshot(storeId, releaseId)` parse the jsonb once into a typed `CatalogSnapshot`. `GET /pos/catalog` takes its store from the POS session.
- The tax rate on a line is the schedule entry with the greatest `effectiveFrom` ≤ the confirmation instant (`CatalogSnapshot.TaxCategory.rateAt`); none → 422 `NO_TAX_RATE_IN_EFFECT`. `ratePercent` is display only.
- A rate added after publication needs a republish: the release is the control point.
- Drafts priced from an older version are refused at confirmation with 409 `CATALOG_CHANGED` and the current `catalogVersion`, so the POS re-reads (§8.4). Confirmed lines keep their snapshot (A3).
- Publish is idempotent (A7 of R1).
