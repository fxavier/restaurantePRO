/**
 * Orders (spec §5 "Atendimento e pedidos", §8.1–§8.3; D34, D35, P13, P15): orders in the modes DINE_IN (seated through a
 * dining table occupation), COUNTER and TAKEAWAY, server-side DRAFT lines priced from the current catalog as a
 * preview, and rounds (batches) whose confirmation snapshots description, price and tax from the catalog and hands
 * the round to production through {@link com.xavier.backend.orders.model.event.OrderBatchConfirmed}.
 *
 * <p>Public API: this root package ({@link com.xavier.backend.orders.OrdersApi} for settlement,
 * {@link com.xavier.backend.orders.OrdersProductionApi} for production, {@link com.xavier.backend.orders.LineAmounts}
 * and the billing views) and the {@code model.event} named interface. Allowed dependencies follow D35: never
 * settlement or production; D99 adds promotions (prices fixed at confirmation, loyalty writes) and customers (the
 * customer of a delivery or loyalty order), and forbids inventory. {@code :: dto} interfaces are named next to their module because the facades of
 * organization, dining and catalog return those types; naming a module alone grants only its root package.
 */
@ApplicationModule(displayName = "Orders", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "dining", "dining :: dto",
        "catalog", "catalog :: dto", "identity", "promotions", "promotions :: dto", "customers", "customers :: dto"})
package com.xavier.backend.orders;

import org.springframework.modulith.ApplicationModule;
