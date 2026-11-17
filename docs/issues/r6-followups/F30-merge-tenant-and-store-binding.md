# F30 — Merge the tenant and store thread bindings (shared ⇄ access class cycle)

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

The tenant binding (`shared.tenancy.TenantScopes`) and the store binding (`access.StoreScopes`) are two thread-local stacks that reference each other, a class-level cycle between `shared` and `access`; `StoreScopes` ≡ `TenantScopes` duplication was already flagged at S71 G5.

## Proposed direction

One binding (tenant + store scope) owned by one package; the other reads it; ArchUnit forbids the cycle.
