# F28 — An explicit per-tenant job runner instead of the advisor's implicit iteration

**Status:** needs-triage
**Source:** G6 architecture review (R6), 2026-11-17
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`StoreScopeAdvisor` runs a reach-ALL `@SystemScoped` method once per active tenant through AOP (leases, own transaction, failure isolation); the iteration is invisible at the job and needed an `invocableClone` fix (S71 G5).

## Proposed direction

A `TenantJobRunner.forEachActiveTenant(job, action)` called explicitly by each job; the advisor keeps only the binding; tests per job stay.
