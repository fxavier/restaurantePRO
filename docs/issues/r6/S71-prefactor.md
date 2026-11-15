# S71 — Prefactor: tenant plumbing, transaction binding, tenancy and organization

**Status:** ready-for-agent
**Blocked by:** None (first; runs alone)

## Goal

After this ticket every transaction is bound to a tenant with `SET LOCAL` semantics, the `tenancy` module and its membership functions exist, the organization module is tenant-isolated, every event carries `tenantId`, jobs run once per active tenant, and every existing test still passes inside the bootstrap tenant. Behaviour for the bootstrap tenant is unchanged.

## Write scope (paths it may touch)

- `backend/pom.xml` (add `spring-boot-starter-oauth2-resource-server`, test `testcontainers-keycloak`, both pinned)
- `src/main/resources/application.yaml`, `src/test/resources/config/application.yaml` (every R6 property of the spec)
- `TestcontainersConfiguration` (+ lazy shared Keycloak container bean)
- `config/persistence/**` (`TenantBindingTransactionManager`; advisor per-tenant iteration and event `tenantId` binding; startup check; remove `StoreScopedDataSource` and its post-processor)
- `shared/tenancy/**` (new), `shared/**` (idempotency rekey + reads in a short transaction, `ChangeNotifier` on `pg_notify`, `StoreProbe`, `StoreScopeViolations` + `RS003`), `shared/web/GlobalExceptionHandler.java`
- `access/**` (`StoreScopes` reads the tenant, `Actor` idempotency scope, `@WithActor` binds the bootstrap tenant)
- `tenancy/**` (new module), `organization/**`
- every `*/model/event/**` record and its publish call (`tenantId` first)
- migrations `shared/V1010`, `organization/V2005`, `tenancy/V15001`
- `deploy/postgres/provision-roles.sql`, `deploy/keycloak/**` (minimal realms)
- tests under `src/test/**` for the above, `architecture/**`
- `docs/architecture/backend-conventions.md` (every §5a row except Web and security)

## Must not touch

`identity/**`, `config/security/**`, any module migration other than shared/organization/tenancy, `deploy/**` other than the two paths above, `CONTEXT.md`, ADRs, `docs/architecture/modules/`

## Named tests (red first)

`TenantBindingTransactionManagerTest`, `TenantScopesTest`, `JobLeaseTest`, `ActorlessTenantScopeTest`, `OrganizationTenantIsolationTest`, `IdempotencyTenantScopeTest`, `NotificationTenantIsolationTest`, `StoreProbeTenantTest`, `RowLevelSecurityCoverageTest` (extended), `CompositeForeignKeyCoverageTest`, `TenantProvisioningChainTest` (organization half), ArchUnit `noTenantIdFromRequests`, `eventsCarryTenantId`, `cachesAreTenantScoped`, `onlyResolutionCreatesTenantContext` (+ `ArchitectureRulesFireTest` fixtures)

## Acceptance criteria

A82, A83 (transaction part), A84 (organization), A88, A90 (mechanism) (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='TenantBindingTransactionManagerTest,TenantScopesTest,JobLeaseTest,ActorlessTenantScopeTest,OrganizationTenantIsolationTest,IdempotencyTenantScopeTest,NotificationTenantIsolationTest,StoreProbeTenantTest,RowLevelSecurityCoverageTest,CompositeForeignKeyCoverageTest,TenantProvisioningChainTest,ArchitectureTests,ArchitectureRulesFireTest'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: software-architect (top model)
- Parallel: No — runs alone; it owns every hotspot.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
