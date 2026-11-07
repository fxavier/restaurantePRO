# 0028. R4 module dependencies (D99 amendments to D65)

Status: Accepted 2026-11-06 (R4 approval: every recommendation, P40–P51). Source: [r4-decisions.md](../design/r4-decisions.md) D99, with D100 (facades) and D132 (lock order). Base: [ADR 0019](0019-r3-module-dependencies.md).

## Context

Stage 4 adds two modules, `promotions` (prices, discount limits, the loyalty ledger, §8.1) and `inventory` (stock, recipes, purchasing, counts), and four couplings: orders asks promotions for the effective price at confirmation (§8.4), orders and dining need a customer (delivery, loyalty, reservations), inventory consumes sale facts (§5) and reporting projects stock facts. Every D35 and D65 forbidden edge, and the reporting read-only rule (D84), must survive.

## Decision

D65 stays, with these amendments:

- `promotions → shared, audit, access, organization(+dto), catalog(root, +dto), customers(root, +dto)` (new). `promotions ↛ orders, settlement, fiscal, cash, production, inventory, dining, reporting`: callers pass the facts it needs (lines, amounts, customer), so earning and redemption are synchronous calls from orders, never promotions listening to orders (that would be a cycle with `orders → promotions`).
- `inventory → shared, audit, access, organization(+dto), catalog(root, +dto, +event), orders(root, +event)` (new, **reads only**). Every facade method inventory calls is `@Transactional(readOnly = true)`. `inventory ↛ settlement, fiscal, cash, customers, promotions, dining, production`.
- `orders → promotions(root, +dto)` and `orders → customers(root, +dto)` (added). `orders ↛ inventory`, as `orders ↛ settlement, production`: stock processing never blocks a sale, and a financial reversal never restores stock (§8.4).
- `dining → customers(root)` (added, read only: a reservation may name a customer).
- `reporting → inventory(root, +event)` (added, reads only). **Nothing but `reporting` depends on `inventory`.**
- `production` and `settlement` are unchanged: production already reads `orders` and its events (delivery slip, D129), settlement calls only the new `OrdersApi` methods (`adjustLinePrice`, D105).
- Facades (D100) follow D36: root packages, reads `readOnly`, writes `MANDATORY` and never authorizing: `PromotionsApi`, `LoyaltyApi`, `InventoryFacts`, and the additions to `OrdersApi`, `OrdersProductionApi`, `CatalogApi`, `CustomersApi` and `DiningApi`.

## Consequences

- Enforcement: each `package-info.java` declares these lists (`ModularityTests.modulesRespectTheirDeclaredBoundaries`). `ArchitectureTests.forbiddenModuleDependenciesAreAbsent` carries `promotions ↛ {orders, settlement, fiscal, cash, production, inventory, dining, reporting}`, `inventory ↛ {settlement, fiscal, cash, customers, promotions, dining, production}`, `orders ↛ inventory` and `nothing but reporting → inventory`. `inventoryCallsOnlyReadOnlyFacadeMethods` mirrors `reportingCallsOnlyReadOnlyFacadeMethods`. Each rule has an `ArchitectureRulesFireTest` fixture in `com.xavier.archfixture.*`.
- Module tests that boot `orders` must also include `promotions` (and its dependencies) in `extraIncludes`, since S46 made pricing a confirmation dependency.
- The module docs in [docs/architecture/modules/](../architecture/modules/) were regenerated in S54 with the `module-promotions` and `module-inventory` pages.
- Proof (S54): `ModularityTests` and `ArchitectureTests` green on the integrated R4 code base.
