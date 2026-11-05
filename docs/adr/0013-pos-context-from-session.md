# 0013. POS context comes from the session, never from the request

Status: Accepted, 2026-11-04 (D39 ★, with D45; from G6 #4). Source: [r2-decisions.md](../design/r2-decisions.md) D39, D45; spec §3, §10.

## Context

R1 POS reads took `?storeId=`. If POS writes did the same, a BackOffice session could create orders with no terminal, and a request could name a store other than the session's.

## Decision

`AccessPolicy.requirePos(Permission p)` returns `PosContext(employeeId, terminalId, storeId)`. It gives 403 unless the actor is an `EMPLOYEE` with a terminal, holds `p` in the session's store, and that store is active. **POS write endpoints never take `storeId` or `terminalId` from the request.** For R1 POS reads, `?storeId` is optional, defaults to the session's store, and gives 403 when it differs.

## Consequences

- As built, the shapes are `requirePos(p)`, `requirePosAny(p...)` (for example the SSE stream: `DINING_VIEW` or `PRODUCTION_VIEW`), `requirePos(p, requestedStoreId)` for reads, and `requireSessionStore(pos, resourceStoreId, entityType, id)` for a resource addressed by id (D42 b; the denial log names the resource). These were consolidated in S7b after catalog, dining and cash had each re-implemented them.
- Store activity (D45): `access.StoreActivity`, implemented by `organization` and cached per request, closes the window before identity's `StoreDeactivated` listener ends the sessions. A module test that calls `requirePos` must provide a `StoreActivity` (include `organization`, or a `@Primary` test bean).
- A BackOffice session calling a POS write gets 403 (A12, `AccessPolicyTest`).
