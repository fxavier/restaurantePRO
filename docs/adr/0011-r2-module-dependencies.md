# 0011. R2 module dependencies (three amendments to D13)

Status: Accepted, 2026-11-04 (D35 ★, with D36 and D37). Source: [r2-decisions.md](../design/r2-decisions.md) D35–D37; spec §6, §7.8. Base: [ADR 0003](0003-module-dependencies-and-access.md).

## Context

R2 adds `orders`, `production`, `cash`, `settlement` and `fiscal`. Voids, expenses, day reopen and reclassification need an approver; production must tell orders a batch was received; document printouts go through the one print queue.

## Decision

- `orders`, `cash` and `settlement` may depend on `identity` (root `ApproverVerifier` only).
- `production` may depend on `orders` (root `OrdersProductionApi.markBatchDispatched`, called synchronously in its listener transaction) and on `fiscal::event`.
- `audit` may depend on `access` (current actor, ADR 0005).
- `orders ↛ settlement`, `orders ↛ production`, `cash ↛ orders`, `cash ↛ settlement`. `settlement` alone sees orders, cash and fiscal together and owns every coordinated close (D37), even where the route is `/pos/orders/{id}/close`.
- Public module APIs are root facades, one per consumer concern (`DiningApi`, `CatalogApi`, `OrdersApi`, `OrdersProductionApi`, `CashApi`, `FiscalApi`). Their writes are `@Transactional(propagation = MANDATORY)` and do not authorize: the calling use case already did (D36).

## Consequences

- As built (`package-info.java`), `orders` also uses `dining :: dto` and `settlement` also uses `fiscal :: dto`, beyond the D35 text (`dining(root)`, `fiscal(root)`): the facades return those view records. Not a new direction, only the named interface.
- `cash` groups expected amounts by `method_code`, a plain string, so it never needs `settlement` (D52).
- Proof: `ModularityTests` (verify plus the injected illegal dependency, A15).
