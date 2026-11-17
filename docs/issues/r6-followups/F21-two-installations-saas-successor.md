# F21 — SaaS successor of the two-installations drill

**Status:** needs-triage
**Source:** S76 drill run
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`test-two-installations.sh` proves isolation between two per-company installations (password login, session cookies). Under SaaS the isolation that matters is between tenants of one installation; the script's credential step also predates Keycloak.

## Proposed direction

Decide whether the drill is retired (tenant isolation is `R6AcceptanceTest` plus the export/restore drills) or rewritten for two SaaS installations (e.g. staging vs production) with Keycloak tokens.
