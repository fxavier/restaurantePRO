/**
 * Inventory ("Stock, receitas e compras", spec §5, §8.2, §8.4; D99, D100): stock items, warehouses, movements and
 * balances, recipes and consumption, counts, transfers, suppliers, purchase orders and goods receipts.
 *
 * <p>Stock consumes order facts through persisted mechanisms (§6): its listeners live in
 * {@code com.xavier.backend.inventory.consumption} and pull rounds and voids through read-only facades. It never
 * writes to orders or catalog ({@code ArchitectureTests.inventoryCallsOnlyReadOnlyFacadeMethods}), it never depends
 * on settlement, fiscal, cash, customers, promotions, dining or production, and only reporting depends on it (D99).
 * Public API: this root package ({@code InventoryFacts}, from S42) and the {@code model.event} named interface
 * ({@link com.xavier.backend.inventory.model.event.StockMovementsPosted}, frozen in S37).
 */
@ApplicationModule(displayName = "Inventory", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "catalog", "catalog :: dto",
        "catalog :: event", "orders", "orders :: event"})
package com.xavier.backend.inventory;

import org.springframework.modulith.ApplicationModule;
