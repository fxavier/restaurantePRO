# BUG-0003: the deploy drills' role check refuses R6's no-privilege `tenancy` schema

- **Status:** open (follow-up [F20](../../issues/r6-followups/F20-drill-role-check-predates-d177.md)).
- **Severity:** High for the release gate, none for the product. Two release drills (`test-two-installations.sh`, A55/A79; `test-upgrade-roles.sh`, A69) stop at their first role check, so the upgrade path and the restore-into-isolation evidence are not re-proven on R6. The database is right: the check is wrong.
- **Breaks:** the R6 frozen contract (PRD r6-multitenancy, "roles": `restaurantpro_app` has **no privilege on `tenancy.*`**; D177, `RowLevelSecurityCoverageTest.theApplicationRoleHasNoPrivilegeOnTenancyTables`). The drill check predates it.
- **Environment:** branch `r6/s76-integrate` at `2d5637d` (main `9817098` + #112 + #113), macOS 15.3 x86_64, 16 CPUs, Docker; the drills build the backend image from this tree.
- **Owner:** `developer` (deploy scripts, `deploy/lib/roles.sh`; S75's area).

## Steps to reproduce

1. `cd deploy && ./test-upgrade-roles.sh --r5-ref e4aecef` (or `./test-two-installations.sh`).

## Expected

The upgraded (or freshly installed) R6 database passes the D163 role check: the owner owns everything, the application role owns nothing, has data privileges on the business tables, read-only on the PLATFORM tables outside the allow-list, and — per the R6 contract — **no privilege at all on `tenancy.*`** (PRD: "`restaurantpro_app` (pool; **no privilege on `tenancy.*`**)").

## Actual

```
--- 3. after the upgrade
FAIL: after the upgrade: 3 violations of D163:
restaurantpro_app on tenancy.platform_audit_event: cannot SELECT, INSERT, UPDATE and DELETE
restaurantpro_app on tenancy.tenant: cannot SELECT, INSERT, UPDATE and DELETE
restaurantpro_app on tenancy.tenant_membership: cannot SELECT, INSERT, UPDATE and DELETE
FAIL: database roles upgrade drill (exit 1)
```

`test-two-installations.sh` fails the same way at step `separation` ("rp-a after up: 3 violations of D163", the same three lines), after passing the container, network and secret separation checks.

## Cause

`deploy/lib/roles.sh` (the shared D163 query) reports every table on which `has_table_privilege('restaurantpro_app', t, 'SELECT, INSERT, UPDATE, DELETE')` is false. With several privileges, PostgreSQL answers true when **any** is held, so the read-only PLATFORM tables of V1012 pass; the `tenancy` tables, on which R6 grants nothing, fail.

## Fix direction

Require no privilege at all on `tenancy.*` (and fail if any is held), keep the current rule elsewhere; re-run both drills.

## Reproducing test

The two drills themselves (no backend test: the backend's own check, `theApplicationRoleHasNoPrivilegeOnTenancyTables`, passes).
