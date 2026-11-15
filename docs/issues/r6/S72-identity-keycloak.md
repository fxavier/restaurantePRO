# S72 — Identity on Keycloak: resource server, tenant resolution, BFF, step-up

**Status:** ready-for-agent
**Blocked by:** S71

## Goal

A BackOffice user logs in through Keycloak (via the BFF, MFA mandatory), the backend accepts only valid JWTs, resolves the tenant from an active membership (with `X-Tenant-Id` as a selection), refuses forged tenants with one body, demands RFC 9470 step-up for sensitive permissions, and support works through time-boxed grants. Server-side sessions, local passwords, local MFA and OIDC federation are gone.

## Write scope (paths it may touch)

- `identity/**`; `config/security/**`; `access/**`; `shared/web/GlobalExceptionHandler.java`
- `bff/**` (new project, own `pom.xml`)
- `deploy/keycloak/**`
- `backend/pom.xml`: **only** deleting the `spring-boot-starter-session-jdbc` line (the recorded exception)
- migrations `identity/V3016`, `shared/V1011`
- the session/CSRF/login test files (~145) → `jwt()` / `TestJwtFactory`
- `docs/architecture/backend-conventions.md` Web and security rows

## Must not touch

any other module, `config/persistence/**`, `shared/tenancy/**`, `application.yaml`, `TestcontainersConfiguration`, `deploy/**` other than `deploy/keycloak/**`, `tenancy/**`

## Named tests (red first)

`JwtValidationTest`, `TenantResolutionTest`, `StepUpTest`, `SupportAccessTest`, `TenantListTest`, `IdpUnavailableTest`, `BffTokenRelayTest`, `IdentityTenantIsolationTest`, `TenantProvisioningChainTest` (identity half, port faked), `KeycloakLoginIT`

## Acceptance criteria

A81, A84 (identity), A85, A87 (see the spec's "Acceptance criteria → slices").

- [ ] Named tests written and red for the expected reason (recorded)
- [ ] Code makes them green; slice tests pass
- [ ] Full suite green as `restaurantpro_app`, 0 skipped
- [ ] G5 review by a fresh reviewer: MERGE, the 10 security rules checked
- [ ] PR to `main` opened (not stacked)

## Verification

```bash
cd backend && rtk mvn test -Dtest='JwtValidationTest,TenantResolutionTest,StepUpTest,SupportAccessTest,TenantListTest,IdpUnavailableTest,IdentityTenantIsolationTest,TenantProvisioningChainTest,KeycloakLoginIT'
cd backend && rtk mvn clean test   # G4: BUILD SUCCESS, 0 failures, 0 errors, 0 skipped, as restaurantpro_app
```

## Agent and parallelism

- Suggested agent: developer (top model)
- Parallel: Yes — with S74a, S74b, S74c, S74d, S75.

## Rules (all R6 tickets)

- Spec: [r6-multitenancy.md](../../prd/r6-multitenancy.md) (APPROVED). Its frozen names, error codes, SQL signatures and Flyway versions are binding; internals are the implementer's call.
- Red first: write the named tests, run them, record the red line and its cause; then the code. Deterministic (injected `Clock`, latches not sleeps), real PostgreSQL, no `@Disabled`, 0 skipped.
- Own worktree and branch from `main` after every blocker has merged; one PR to `main`, never stacked; the user merges.
- G5: a fresh code reviewer (top model) that never saw the work checks the spec's **10 security rules** and runs mutants on every new rule.
- Stop and ask the user on any condition of the spec's "Stop and ask" list.
- Shell commands through `rtk`; `rtk git add <explicit paths>` only.
