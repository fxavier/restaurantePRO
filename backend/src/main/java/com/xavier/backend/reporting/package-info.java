/**
 * Reporting ("Relatórios", spec §5, §8.1 SalesRecord, §8.6; D64, D84–D87, P37): read-only projections of the
 * immutable facts other modules own, and the reports over them. Each owner publishes a thin event; a listener here
 * pulls the fact through a read-only facade ({@code FiscalApi.documentFacts}, {@code OrdersApi.voidFacts},
 * {@code SettlementFacts}) and inserts its projection row keyed by the source id ({@code ON CONFLICT DO NOTHING}), so
 * a redelivery changes nothing. A rebuild deletes a store's projection rows over a business-date range and pulls the
 * owners' paged id listings through the same code path.
 *
 * <p>Sales belong to their order's business day and money to its session's cash day (D85); reports filter by
 * business date only. Every report carries its freshness ({@code asOf}, {@code pendingFacts}) and finality (D86).
 * Credit is never money received (D87). A SalesRecord never creates a receipt or stock (§8.1).
 *
 * <p>Nothing depends on reporting (D65), and it calls only read-only facade methods
 * ({@code ArchitectureTests.reportingCallsOnlyReadOnlyFacadeMethods}); its own writes are its projections and the
 * audit records of its exports and rebuilds. Allowed dependencies follow D65, plus {@code audit} (the export is
 * audited, D86) and {@code dining :: dto} ({@code DiningApi.tableInfo} returns {@code TableInfo}).
 */
@ApplicationModule(displayName = "Reporting", allowedDependencies = {
        "shared", "access", "audit", "organization", "organization :: dto", "identity", "dining", "dining :: dto",
        "catalog", "catalog :: dto", "orders", "orders :: event", "fiscal", "fiscal :: dto", "fiscal :: event",
        "settlement", "settlement :: event", "cash"})
package com.xavier.backend.reporting;

import org.springframework.modulith.ApplicationModule;
