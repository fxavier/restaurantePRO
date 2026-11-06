# 0023. Reporting projections fed by event-triggered pulls

Status: Accepted 2026-11-05. Source: [r3-decisions.md](../design/r3-decisions.md) D84, with D85–D87.

## Context

Reports (§5 "Relatórios", §8.6) need sales, taxes, receipts, credit and voids across modules, fast over ranges, without cross-schema SQL (D6) and without a second live code path for backfill.

## Decision

- Each owner publishes a **thin** event in the fact's transaction (`FiscalDocumentIssued`, `OrderLinesVoided`, `PaymentRecorded`, `RefundRecorded`, `CreditChargePosted`). A reporting `@ApplicationModuleListener` **pulls** the immutable fact through a read-only facade and upserts its projection row keyed by the source id (`ON CONFLICT DO NOTHING`), so it is idempotent.
- **Rebuild and backfill use the same path**: `POST /backoffice/reports/rebuild {storeId, from, to}` deletes the store/range projection rows and pulls the paged id listings.
- Tables in `reporting`: `sales_record(_line, _tax)`, `payment_record` (payments and refunds, signed), `credit_record`, `void_record`, `rebuild_run`. Dimensions are resolved at projection time and stored.
- Every response carries `asOf`, `pendingFacts` and `final` (D86). Credit is a separate column, never money received (D87).

## Consequences

- Tax per rate is read from the documents; after D96 that equals the order's tax except for open pending corrections.
- D86 target measured in S33 (`R3ReportBenchmark`, 150 k documents, 600 k lines): every 31-day report p95 ≤ 258 ms (< 1 s), every 1-day report p95 ≤ 18 ms (< 300 ms); no rollup tables are needed.
- Review backlog not done in S33 (production code): `OrderBillingView.mode` to tell COUNTER from TAKEAWAY, and a tax code on fiscal's `TaxSummary` so one rate cannot split into two rows.
- Proofs: `SalesProjectionTest`, `RebuildEqualsLiveTest`, `ReportFreshnessTest`, `ReportPlanTest`; end to end, `R3AcceptanceTest` (sales by item, hour, employee and area equal the documents; taxes per rate = Σ `fiscal_document_tax`; receipts by method = Σ (PAYMENT_RECEIPT − REFUND); on account 150.00 separate; `final`; rebuild from empty identical), `RestartMatrixTest` (f).
