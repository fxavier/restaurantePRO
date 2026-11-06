# 0017. Fiscal adapter port, with a non-certified guard

Status: Accepted, 2026-11-04 (D54 ★, with B4, P7, P8 and D50 §7.6). Source: [r2-decisions.md](../design/r2-decisions.md) D54, D50; spec §5, §7.6, §14 #10.

## Context

No market is chosen (P7). A certified fiscal integration for the market is a launch condition (§5, §14 #10), but R2 must issue documents with gapless numbering.

## Decision

`fiscal.FiscalAdapter` is a root SPI: `code()`, `certified()`, `issue(IssueRequest)` (called inside the issuing transaction under the series lock: local work only, no network) and `printout(IssuedDocument, boolean copy)`. R2 ships the `internal` adapter, **not for production**: it chains SHA-256 over (previous hash of the series, number, date, total) into `adapter_data`, an integrity aid, not a certification. Startup selects `restaurantpro.fiscal.adapter` and refuses to start with a non-certified adapter unless `restaurantpro.fiscal.allow-non-certified=true`. The health component `fiscal` reports `NON_CERTIFIED`, and every printout carries "DOCUMENTO NÃO FISCAL". One document type, `SALE`, numbered from a per-store series (P8).

Rejected: issuing through a remote authority inside the numbering lock (§7.3 forbids an external call under a lock). A remote adapter will issue locally and transmit afterwards (ADR 0016), designed in the market run.

## Consequences

- Numbering takes the document lock, then the series lock, then `next_number`, inside the committing transaction: a rollback leaves no gap, no `SEQUENCE` is used, `UNIQUE(series_id, number)` is the backstop (`GaplessNumberingTest`).
- An issue failure leaves the DRAFT with `last_error` as a visible pendency (422 `FISCAL_PENDING` with `documentId`); payments are kept and nobody is charged again.
- `NON_CERTIFIED` is not in the health aggregation order, so it never turns the overall status DOWN.
- B4 stays open: the certified adapter gates launch.
