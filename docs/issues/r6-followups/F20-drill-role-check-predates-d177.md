# F20 — Deploy drills' D163 role check refuses R6's no-privilege `tenancy` schema

**Status:** needs-triage
**Source:** S76 drill run; QA bug `docs/qa/bugs/0003-drill-role-check-predates-d177.md`
**Recorded by:** S76 (R6 integrate), 2026-11-17

## Problem

`deploy/lib/roles.sh` requires `restaurantpro_app` to hold some of SELECT/INSERT/UPDATE/DELETE on every table, but R6 (frozen contract, D177) gives it none on `tenancy.*`, so `test-two-installations.sh` and `test-upgrade-roles.sh` fail at their first role check.

## Proposed direction

In `roles.sh`, require **no** privilege on `tenancy.*` (and keep requiring data privileges elsewhere, read-only on PLATFORM tables); re-run both drills.
