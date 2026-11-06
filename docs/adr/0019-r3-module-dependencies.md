# 0019. R3 module dependencies (D65 amendments to D35)

Status: Accepted 2026-11-05 (R3 approval: every recommendation). Source: [r3-decisions.md](../design/r3-decisions.md) D65. Base: [ADR 0011](0011-r2-module-dependencies.md).

## Context

Stage 3 adds two modules (`customers`, `reporting`) and three new couplings: settlement coordinates credit sales, production prints a debt-payment slip, and reporting projects facts it does not own. D13 had planned `fiscal → customers`.

## Decision

D35 stays, with these amendments:

- `customers → shared, audit, access, organization(+dto)`, and `customers ↛ orders, settlement, fiscal, cash, production`. Customers owns identity, limit and ledger only.
- `settlement → customers(root, +dto)` (added). Settlement reads the `CustomerSnapshot` and passes it to fiscal as the document's `Buyer`.
- `fiscal ↛ customers` (the D13 plan is dropped). A document needs only the snapshot (§8.2).
- `production → settlement::event` (added, events only): the debt-payment slip goes through the one print queue (D75). No cycle: settlement never depends on production.
- `reporting → shared, access, organization(+dto), identity, dining, catalog(+dto), orders(+event), fiscal(+dto, +event), settlement(+event), cash` (new), and **nothing depends on reporting**. Reporting calls only read-only facade methods.

## Consequences

- Enforcement: each `package-info.java` declares its lists (`ModularityTests`); `ArchitectureTests.forbiddenModuleDependenciesAreAbsent` carries `fiscal ↛ customers`, `customers ↛ {orders, settlement, fiscal, cash, production}` and `nothing → reporting`; `reportingCallsOnlyReadOnlyFacadeMethods` fails on a call to a facade method that is not `@Transactional(readOnly = true)` (`ReportingReadOnlyRuleTest`). Every rule has an `ArchitectureRulesFireTest` fixture (A32).
- The pending-work views stay client-composed, so `nothing → production` holds.
- Proof (S33): `ModularityTests`, `ArchitectureTests`, `ArchitectureRulesFireTest` green on the integrated R3 code base.
