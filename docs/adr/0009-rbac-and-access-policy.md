# 0009. RBAC with code-defined permissions, checked by `AccessPolicy` in every use case

Status: Accepted, 2026-11-04 (D27, with the POS store-scope decision of 2026-11-04 and the D13 access amendment). Source: [backend-decisions.md](../design/backend-decisions.md) D27, [r2-decisions.md](../design/r2-decisions.md) D39, D42, D62; spec §3, §10.

## Context

Spec §3 and §10: authorization is server-side, per use case, scoped by store and terminal. Hiding a button is not authorization.

## Decision

`access.Permission` is a code-defined, append-only enum, because a permission is tied to a code path. `Role` is data (a named set of permissions), seeded with the profiles of §3 and editable. `EmployeeStoreAssignment(employee, store, role)` gives per-store scope. Authorities are `PERM_X` (all stores) and `PERM_X@<storeId>`. Every use case starts with an `AccessPolicy` call; controllers never authorize. A POS session (PIN on a terminal) gets only the authorities of the terminal's store; a BackOffice session carries all assigned stores.

## Consequences

- As built, the policy has one method per shape of check: `require(p, storeId)` / `require(p)` (BackOffice), `requirePos(p)` → `PosContext` and `requirePosAny(...)` (ADR 0013), `requirePos(p, requestedStoreId)` for POS reads with an optional `?storeId`, `requireSessionStore(pos, resourceStoreId, entityType, id)` for a POS resource addressed by id, and `requireAny(storeId, p...)`. Code never inspects `actor.authorities()` by hand. Denials are logged at WARN.
- Store-scope order (D42): when the store is an input, authorize before any read; for a resource addressed by id, read only its `storeId` (404), then authorize (403). Never load fully, then authorize. The G5 reviews fixed cash, dining and settlement use cases that locked or loaded first.
- A new permission needs a migration that grants it to the administrator role (`RoleSeedTest`); R2 added 23 (D62, `V3005`).
- Proof: `AccessPolicyTest`, `SecurityConfigTest`, and per-module 403 tests (A12).
