# S74d — Tenant isolation: catalog, promotions, reporting, audit

**Status:** ready-for-agent
**Blocked by:** S71

## Goal

Two tenants with identical codes use catalog, promotions, reporting, audit side by side and each sees only its own rows through every route, report, listener and job; the database refuses cross-tenant reads, inserts, `tenant_id` updates and child references even with `AccessPolicy` bypassed. `ReleaseFamilies` is keyed `(tenantId, releaseId)`; `RebuildRunner` moves into transactions.

## Write scope (paths it may touch)

- `catalog/**` (repositories, entities, probes, listeners, tests)
- `promotions/**` (repositories, entities, probes, listeners, tests)
- `reporting/**` (repositories, entities, probes, listeners, tests)
- `audit/**` (repositories, entities, probes, listeners, tests)
- migrations `catalog/V5009`, `promotions/V12024`, `reporting/V14007`, `audit/V1107` (the spec's per-table recipe: no `UPDATE`)

## Must not touch

`pom.xml`, `application.yaml`, `config/**`, `shared/**`, `access/**`, `tenancy/**`, `identity/**`, `organization/**`, any other module, `deploy/**`, docs

## Named tests (red first)

`CatalogTenantIsolationTest` (same item code and barcode in A and B), `PromotionsTenantIsolationTest`, `ReportingTenantIsolationTest`, `AuditTenantIsolationTest`, `TenantCacheIsolationTest`, `RebuildRunnerTenantTest`; the module's `<Module>RowLevelSecurityTest` green inside tenant A; its listeners and jobs in `<Module>ActorlessScopeTest`; plan tests for its hot queries in tenant A with B's rows present

## Acceptance criteria

A80, A82, A84 (its tables), A88 (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='CatalogTenantIsolationTest,PromotionsTenantIsolationTest,ReportingTenantIsolationTest,AuditTenantIsolationTest,TenantCacheIsolationTest,RebuildRunnerTenantTest,CatalogRowLevelSecurityTest,RowLevelSecurityCoverageTest,CompositeForeignKeyCoverageTest'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: developer (mid tier; G5 by the top model)
- Parallel: Yes — with S72, S75 and the other S74 tickets.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
