# F31 — Split `IdentityProviderAdmin` per realm and drop `TenancyApi.idp()`

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

One port mixes the tenant realm (organizations, invites, sessions) and the devices realm (clients, keys); callers reach it through `TenancyApi.idp()`, so a module that needs device clients can also invite users.

## Proposed direction

Two ports (`TenantRealmAdmin`, `DeviceRealmAdmin`) injected where needed; remove `TenancyApi.idp()`; the fakes split accordingly.
