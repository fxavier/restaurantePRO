# S75 — Platform and deploy: tenant lifecycle API, Keycloak admin adapter, drills

**Status:** ready-for-agent
**Blocked by:** S71

## Goal

A platform operator onboards a company (tenant, first store, administrator invited through Keycloak) in one idempotent command, suspends and reactivates it with effect on the next request and on jobs, and cannot reach business data. The dev Compose runs Keycloak; tenant export, the tenant-restore drill and the PgBouncer transaction-mode drill pass; the B8 spike is documented for the user to run.

## Write scope (paths it may touch)

- `tenancy/**` (platform controllers, `RestClient` `IdentityProviderAdmin` replacing the placeholder)
- migration `tenancy/V15002`
- `deploy/**` except `deploy/keycloak/**` and `deploy/postgres/provision-roles.sql` (Keycloak + its PostgreSQL in Compose, `tenant-export.sh`, tenant-restore drill, `drills/pgbouncer/**`, `drills/b8-rds/README.md`)
- `backend/compose.yaml`, `backend/run-local.sh`

## Must not touch

`pom.xml`, `application.yaml`, `config/**`, `shared/**`, `access/**`, `identity/**`, `organization/**`, every business module, `deploy/keycloak/**`, `provision-roles.sql`

## Named tests (red first)

`PlatformTenantLifecycleTest`, `PlatformAuthorizationTest`, `IdentityProviderAdminIT` (O1 evidence: the provisioner cannot change realm settings), `PgBouncerTransactionModeTest` (drill), `deploy/test-tenant-export.sh`, `deploy/test-tenant-restore.sh`

## Acceptance criteria

A83 (PgBouncer), A90 (suspension), A91 (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='PlatformTenantLifecycleTest,PlatformAuthorizationTest,IdentityProviderAdminIT'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: developer (top model); qa-engineer for the drills
- Parallel: Yes — with S72 and S74a–d. Also run the drills: `rtk proxy deploy/test-tenant-export.sh`, `rtk proxy deploy/test-tenant-restore.sh`, the PgBouncer drill. The B8 spike itself is **not** run by the agent.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
