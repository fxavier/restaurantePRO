/**
 * Promotions and loyalty ("Promoções e fidelização", spec §5, §8.4; D99, D100): promotion rules published as
 * releases, the pricing that fixes a line's effective price at confirmation, discount limits, and the loyalty
 * program with its points ledger.
 *
 * <p>Public API: this root package ({@code PromotionsApi}, {@code LoyaltyApi}, from S39 and S40) and the
 * {@code model.dto} named interface (the frozen pricing contract, S37). Its callers pass the facts it needs (lines,
 * amounts, customer), so it never depends on orders, settlement, fiscal, cash, production, inventory, dining or
 * reporting (D99, {@code ArchitectureTests.forbiddenModuleDependenciesAreAbsent}).
 */
@ApplicationModule(displayName = "Promotions", allowedDependencies = {
        "shared", "audit", "access", "organization", "organization :: dto", "catalog", "catalog :: dto",
        "customers", "customers :: dto"})
package com.xavier.backend.promotions;

import org.springframework.modulith.ApplicationModule;
