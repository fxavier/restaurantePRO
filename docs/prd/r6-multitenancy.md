# PRD R6 — SaaS multi-tenancy: `tenant_id`, RLS bound with `SET LOCAL`, Keycloak (backend, BFF, deploy)

Status: **APPROVED** 2026-11-15 by Xavier Francisco ("aprovado", R6 G2). ready-for-agent. Sources: [multitenancy-decisions.md](../design/multitenancy-decisions.md) (**APPROVED** 2026-11-15 with its R6 G1 amendments; cited as Dn, Pn, An, On), [ADR 0040](../adr/0040-saas-multitenancy-tenant-id-rls-keycloak.md), the ADRs it supersedes or amends (0007, 0008, 0013, 0034, 0035, 0039), [rls-decisions.md](../design/rls-decisions.md), [backend-decisions.md](../design/backend-decisions.md) and r2–r5, [product_description.md](../product_description.md) as amended on 2026-11-15 (cited as §n), [CONTEXT.md](../../CONTEXT.md), [backend-conventions.md](../architecture/backend-conventions.md). Baseline: `main` at f32e4b5 (the RLS run S64–S70 merged; suite 3354/0/0/0).

**This run starts after the R6 planning PR has merged.** Base package `com.xavier.backend`, layout D12. Every slice is built with TDD, **red first**: the named tests are written and fail, for the reason the slice expects, before the code; the red line and its cause are recorded in the PR. Tests are deterministic (injected `Clock`, fixed ids where order matters, **latches, not sleeps**; concurrency tests use latches and real PostgreSQL). **Real PostgreSQL only** (Testcontainers `postgres:18.6-alpine`), every test as `restaurantpro_app`. No `@Disabled`, no assumption that skips; `cd backend && rtk mvn clean test` is BUILD SUCCESS with 0 failures, 0 errors and **0 skipped** at every slice exit.

- **Every slice PR targets `main`; none is stacked.** A slice branches from `main` once every slice it is blocked by has merged. Within a group, merge in ascending slice id, with the full suite after each merge.
- **Only S71** edits `backend/pom.xml` (one recorded deletion by S72, below), `application.yaml` (main and test), `TestcontainersConfiguration`, `config/persistence/**`, `shared/tenancy/**`, `deploy/postgres/provision-roles.sql`. Every dependency R6 needs is added by S71 (below), so no later slice touches `pom.xml`.
- **Hotspot owners** (one writer per group): `shared/**` except `shared/tenancy` → S71; `access/**` → S71, then S72, then S73 (serial); `shared/web/GlobalExceptionHandler.java` → S71, then S72; `config/security/**` → S72; `organization/**` → S71, then S73; `identity/**` → S72, then S73; `production/**` → S74a, then S73; `tenancy/**` → S71, then S75; `deploy/keycloak/**` → S71, then S72; every other `deploy/**` → S75; `bff/**` (new project, own `pom.xml`) → S72; `*/model/event/**` and their publish calls → S71; `docs/architecture/backend-conventions.md` → S71 (all rows but Web and security), S72 (Web and security); `CONTEXT.md`, ADR statuses, `docs/architecture/modules/` (D44) → S76 only. Slices propose glossary terms in their PR text.
- The names below are binding. Internals are the implementer's call.

## Problem statement

restaurantPRO was built as one installation per company. The product is now a SaaS (§1, §2 amended): every company runs on one central installation, so one forgotten filter, one trusted request field, one cache key or one job without a tenant would show one company's sales, prices, customers or fiscal documents to another. Isolation must hold in the application **and** in the database, the identity provider must be Keycloak, and POS terminals and printers must work from a central backend that cannot reach a restaurant's LAN.

## Solution

Every tenant-owned row carries `tenant_id`; PostgreSQL enforces a restrictive tenant policy on every tenant table, ANDed with the existing store policies. The backend becomes an OAuth2 resource server for Keycloak JWTs, resolves the tenant only from an authorised membership, and binds it with `SET LOCAL` at transaction begin, so nothing reaches a protected table unbound. Terminals and print bridges are Keycloak confidential clients; the BackOffice talks through a thin BFF; jobs and listeners always run inside one tenant.

## User stories

1. As a company owner, I want my company's data invisible to every other company on the platform, so that sharing the platform never exposes my business.
2. As a company owner, I want to use the same store, item, employee and fiscal-series codes another company uses, so that onboarding never forces me to rename anything.
3. As a manager, I want a resource of another company addressed by id to look like it does not exist, so that ids never reveal other companies.
4. As a manager, I want a resource of another store of my own company to answer "forbidden", so that I know it exists but I lack the permission (D42).
5. As a BackOffice user, I want to log in once through Keycloak with MFA, so that one strong login protects fiscal, price and user changes.
6. As a BackOffice user, I want my tokens kept out of the browser's JavaScript, so that an XSS cannot steal my authority (P69).
7. As an accountant with several client companies, I want to choose which company I am working in, so that I never act in the wrong one by accident.
8. As an accountant, I want the list of my companies to show only mine, so that no one learns which other companies exist.
9. As a security officer, I want a forged tenant header, body field or path value to be refused the same way for an existing and a random tenant, so that there is no tenant oracle.
10. As a manager, I want a sensitive action (fiscal, prices, users) to ask me to re-authenticate if my second factor is older than five minutes, so that an unattended session cannot change them.
11. As a manager, I want my sensitive request to run exactly once after re-authenticating, so that a step-up never duplicates a price change or a document.
12. As an administrator, I want to enrol a POS terminal with a one-time code, so that no long-lived shared secret exists on the device.
13. As an administrator, I want a revoked terminal refused on its next request, so that a stolen device stops working at once.
14. As a waiter, I want to log in on the terminal with my code and PIN as today, so that the floor workflow does not change.
15. As a cashier, I want the receipt printer and cash drawer to keep working with a central backend, so that the move to SaaS does not stop the counter.
16. As a restaurant IT person, I want printing to need no inbound port on the store network, so that the LAN stays closed.
17. As an administrator, I want a print bridge of one store unable to take another store's or company's jobs, so that tickets never print in the wrong place.
18. As a support person, I want a time-boxed grant from the company administrator to act in that company, so that I can help without permanent access.
19. As a company administrator, I want every support action audited in my company and to be notified when support starts, so that support is visible to me.
20. As a platform operator, I want to onboard a company with its first store and administrator in one idempotent command, so that onboarding is repeatable and recoverable.
21. As a platform operator, I want to suspend a company and have every request and job for it stop on the next request, so that an unpaid or abusive tenant is cut off at once.
22. As a platform operator, I want no way to read a company's business data through the platform API, so that operators cannot browse customers' data.
23. As an operator, I want jobs to run for every active company, skip suspended ones, and continue when one company fails, so that one tenant cannot stop the others.
24. As an operator running two backend instances, I want a job never to run twice for the same company at the same time, so that sweeps and reconciliations stay correct.
25. As an operator, I want to export one company's data and restore one company to a point in time without touching the others, so that contract end and incidents are handled per company.
26. As an operator, I want the database connection to carry no tenant after a transaction, including behind PgBouncer in transaction mode, so that pooled connections never mix companies.
27. As a developer, I want a protected table read outside a transaction to fail, so that a missing binding is found in tests, not in production.
28. As a developer, I want new rows to take the bound tenant automatically, so that no use case has to pass or can forget a tenant.
29. As a developer, I want ArchUnit to refuse a `tenantId` parameter in a controller or request DTO, an event without `tenantId`, and a cache outside the allow-list, so that the rules hold as the code grows.
30. As a developer, I want the existing tests to keep running in a bootstrap tenant without rewriting each one, so that the run's cost stays in the new isolation suites.
31. As an auditor, I want the backend to start while Keycloak is down and answer 503 until it can validate tokens, so that an IdP outage never weakens validation.
32. As a company owner, I want p95 under 500 ms with 20 companies of 10 terminals each, so that sharing the platform does not slow my counter (§12).

## Implementation decisions (frozen contracts)

| name | value |
|---|---|
| roles | unchanged (D163/D188): `restaurantpro` (ops superuser, never held by the backend), `restaurantpro_owner` (Flyway, `BYPASSRLS` unless B8 forces fallback (b)), `restaurantpro_app` (pool; **no privilege on `tenancy.*`**; `statement_timeout = 15s`, `idle_in_transaction_session_timeout = 60s`) |
| bootstrap tenant | id `00000000-0000-7000-8000-000000000001` (literal in the migrations and in `TenantTestSupport.BOOTSTRAP`), code from `company_profile` tax id, or `bootstrap` on an empty database |
| settings | `app.tenant_id`, `app.all_stores`, `app.store_ids`, `app.scope_origin`, all transaction-local (D178) |
| SQLSTATEs | `RS001` store scope not set (unchanged); **`RS003` tenant not set** (raised by `shared.rls_tenant_id()`; a distinct state so the tenant and store failures are told apart — amends D178's "raises `RS001`"); `RS002` store or tenant change refused; `42501` from `WITH CHECK` |
| headers | `Authorization: Bearer <JWT>` (every API call); `X-Tenant-Id: <uuid>` (selection only, BackOffice and support, D177); `X-Pos-Session: <opaque>` (every POS call after login, D182); `Idempotency-Key` (D24, unchanged); approver headers (D28, D40, unchanged). `X-Terminal-Token` is removed. |
| RFC 9470 challenge | `401` + `WWW-Authenticate: Bearer error="insufficient_user_authentication", error_description="A different authentication level is required", acr_values="2", max_age=300` + ProblemDetail `MFA_STEP_UP_REQUIRED` (D184) |
| properties | `restaurantpro.security.issuers.tenant` ← `RESTAURANTPRO_OIDC_TENANT_ISSUER`; `restaurantpro.security.issuers.ops` ← `RESTAURANTPRO_OIDC_OPS_ISSUER`; `restaurantpro.security.audience` = `restaurantpro-api`; `restaurantpro.security.clock-skew` = `PT30S`; `restaurantpro.security.step-up-max-age` = `PT5M`; `restaurantpro.keycloak.admin.base-url`, `.realm`, `.client-id` = `tenancy-provisioner`, `.key-file` ← `RESTAURANTPRO_KEYCLOAK_ADMIN_KEY_FILE`; `restaurantpro.jobs.lease-ttl` = `PT2M`; `restaurantpro.jobs.batch-per-tenant` = `100`. All added by S71; unset issuers make every API call 503 `IDP_UNAVAILABLE` (fail closed). |
| dependencies | **added by S71 only**: `spring-boot-starter-oauth2-resource-server`; test: `com.github.dasniko:testcontainers-keycloak` (pinned, Keycloak 26.x image pinned). No Keycloak admin client library: the adapter uses `RestClient`. **One recorded exception:** S72 deletes the `spring-boot-starter-session-jdbc` line together with the last code that uses it (a deletion; no other slice of group 1 edits `pom.xml`). The new `bff/` and `bridge/` projects have their own `pom.xml`. |

**Error codes and HTTP mapping** (ProblemDetail `code`):

| code | HTTP | when |
|---|---|---|
| (`invalid_token`) | 401 | any `JwtValidationTest` case (D176), standard Bearer challenge |
| `IDP_UNAVAILABLE` | 503 | no JWKS key cached and JWKS unreachable, or issuers unset |
| `TENANT_SELECTION_INVALID` | 400 | malformed `X-Tenant-Id` |
| `TENANT_SELECTION_REQUIRED` | 400 | several memberships, no header (lists nothing) |
| `TENANT_FORBIDDEN` | 403 | no matching active membership — same body for an existing foreign tenant and a random UUID |
| `TENANT_SUSPENDED`, `TENANT_CLOSED` | 403 | the one matching tenant is not ACTIVE |
| `MFA_STEP_UP_REQUIRED` | 401 | RFC 9470 challenge above |
| `MFA_ACCOUNT_REQUIRED` | 403 | sensitive permission from a PIN session (D142 as amended) |
| `POS_SESSION_INVALID` | 401 | `X-Pos-Session` missing, expired, or of another terminal than the token's membership |
| `ENROLMENT_CODE_INVALID` | 400 | unknown, used, expired or burned code (one answer for all) |
| `ENROLMENT_THROTTLED` | 429 | more than 10 enrolment attempts per minute per source address |
| `PLATFORM_FORBIDDEN` | 403 | `/platform/**` without an ops-realm token with `platform-operator` |
| `TENANT_SCOPE_MISSING` | 500 | `RS003` (alert, D170 pattern) |
| `TENANT_SCOPE_VIOLATION` | 500 | `42501` on a tenant policy, CRITICAL alert |
| `STORE_SCOPE_MISSING`, `STORE_SCOPE_VIOLATION`, `STORE_CHANGE_REFUSED` | 500 | unchanged (RLS run) |

**Java contracts:**

| contract | detail |
|---|---|
| `shared.tenancy.TenantContext` | final value class `(UUID tenantId, MembershipKind kind, UUID principalId, String origin)`; `MembershipKind {ACCOUNT, TERMINAL, BRIDGE, SUPPORT, SYSTEM}`; created only by `config.security`, `shared.tenancy.TenantScopes` and test sources (ArchUnit `onlyResolutionCreatesTenantContext`) |
| `shared.tenancy.TenantScopes` (`@Component`) | `Optional<TenantContext> current()`; `UUID requireTenant()` (throws when unbound); `void run(UUID tenantId, SystemScope, Runnable)`, `<T> T call(UUID tenantId, SystemScope, Supplier<T>)`; `Runnable propagate(Runnable)` (tenant + store scope); `run`/`call` throw `IllegalStateException` when a transaction is active (as D165) |
| `shared.tenancy.JobLeases` | `boolean tryAcquire(String job, UUID tenantId)` (D185 compare-and-set, ttl from properties) |
| `config.persistence.TenantBindingTransactionManager extends JpaTransactionManager` | the only `PlatformTransactionManager` bean; applies `TransactionManagerCustomizers`; `doBegin` → `super.doBegin` → one `SELECT set_config(…,true)×4` on `DataSourceUtils.getConnection(getDataSource())`; failure → rollback + `CannotCreateTransactionException`; no context → binds `''` (D178 as amended). Replaces `StoreScopedDataSource` and its post-processor. |
| `config.persistence.StoreScopeAdvisor` (extended) | listeners: binds the event's `tenantId()` + `storeId()` before the `REQUIRES_NEW` transaction; an event without `tenantId` fails before the transaction. `@SystemScoped` with reach `ALL`: the advisor invokes the method **once per active tenant** (`active_tenant_ids()`, rotating start, `JobLeases.tryAcquire`), each in its own bound transaction; one tenant's failure is counted and alerted and does not stop the others. Reach `NONE`: once, unbound (PLATFORM tables only). No module job changes code. |
| `tenancy.TenancyApi` | `List<Membership> memberships(String issuer, String subject)`; `Optional<Membership> resolve(String issuer, String subject, UUID requested, Set<MembershipKind> kinds)`; `UUID grant(MembershipGrant)` (bound tenant only); `void revoke(UUID membershipId)`; `TenantStatus status(UUID tenantId)`; `void activate(UUID tenantId)`; `List<UUID> activeTenantIds()`; `IdentityProviderAdmin idp()` |
| `tenancy.IdentityProviderAdmin` | port: organizations, organization members, user invite (execute-actions email), user sessions (list, logout), `pos-*`/`bridge-*` clients (create with `client-jwt`, register public JWK, disable). S71 ships `UnavailableIdentityProviderAdmin` (throws → 503 `IDP_UNAVAILABLE`); S75 replaces it with the `RestClient` adapter. |
| events (`tenancy.model.event`) | `TenantProvisioned(tenantId, companyName, taxId, country, currency, firstStoreName, adminEmail)`, `TenantSuspended`, `TenantReactivated`, `TenantClosed`, `MembershipRevoked`; `organization.model.event.CompanyProvisioned(tenantId, storeId, adminEmail)`; every event record of every module gains `UUID tenantId` as its first component (S71) |
| module edges (D13 amended) | `tenancy → shared, audit`; `organization → tenancy`; `identity → tenancy`; `config → tenancy`. **Onboarding is a chain of events, not a call from `tenancy`** (amends D181 to avoid an `identity ⇄ tenancy` cycle): platform command → `TenantProvisioned` → `organization` listener creates the company profile and first store → `CompanyProvisioned` → `identity` listener creates the local administrator (Administrator, all stores), invites the Keycloak user, grants the `ACCOUNT` membership and calls `TenancyApi.activate`. Each external call follows D48. |
| platform writes | **no owner pool** (amends D181): `/platform/**` writes go through `SECURITY DEFINER` functions `tenancy.create_tenant`, `tenancy.set_tenant_status`, `tenancy.record_platform_event`; the owner credentials never enter the backend process. ArchUnit `onlyTenancyAdapterUsesOwnerDataSource` is therefore not built. |

**SQL contracts** (all `SECURITY DEFINER` functions `SET search_path = pg_catalog`, `EXECUTE` revoked from `PUBLIC` and granted to `restaurantpro_app`; the allow-list is pinned by `RowLevelSecurityCoverageTest`):

| object | signature / rule | block |
|---|---|---|
| `shared.rls_tenant_id()` | `RETURNS uuid STABLE PARALLEL SAFE`; raises `RS003` when unset or `''` | V1010 |
| `shared.rls_protect_tenant(t regclass)` | `ENABLE`+`FORCE`; restrictive `tenant_scope` `FOR ALL` `USING`/`WITH CHECK (tenant_id = (SELECT shared.rls_tenant_id()))`; `tenant_fixed` `BEFORE UPDATE OF tenant_id` → `RS002`; asserts `tenant_id` is `NOT NULL DEFAULT shared.rls_tenant_id()` and `UNIQUE (tenant_id, id)` exists; registers the table | V1010 |
| `rls_protect_store_table`, `rls_protect_store_or_all_table`, `rls_protect_child_table`, `rls_protect_store_or_all_child_table` | call `rls_protect_tenant` first, then unchanged | V1010 |
| `shared.rls_register_platform(t regclass, reason text)`, table `shared.rls_platform_table` | PLATFORM class (D179); `rls_unscoped_table` empties as S74 converts tables and is dropped by S76 | V1010 |
| `shared.store_of(regclass, uuid)` | also requires `tenant_id = rls_tenant_id()`: other tenant → `NULL` → 404 | V1010 |
| `inventory.stock_transfer_stores` | same tenant condition | V13082 |
| `shared.job_lease` + `shared.try_lease(job text, tenant uuid, holder text, ttl interval) RETURNS boolean` | D185 as amended; PLATFORM | V1010 |
| `shared.idempotency_record` | TENANT class; key `(tenant_id, scope, key)` | V1010 |
| `tenancy.tenant`, `tenancy.tenant_membership` | D174, D177 (+ check `kind = 'SUPPORT'` ⇒ `valid_until` set and `≤ valid_from + 24 h`); PLATFORM; no grant to the app role | V15001 |
| `tenancy.resolve_membership(p_issuer text, p_subject text, p_requested uuid, p_kinds text[])` | `RETURNS TABLE (tenant_id uuid, kind text, principal_id uuid, tenant_status text)`, ACTIVE and valid at `now()` only | V15001 |
| `tenancy.active_tenant_ids()` | `RETURNS SETOF uuid`, ACTIVE only | V15001 |
| `tenancy.grant_membership(…)`, `tenancy.revoke_membership(uuid)` | refuse unless the target tenant equals `rls_tenant_id()` (`RS003`/`42501`) | V15001 |
| `tenancy.create_tenant(…)`, `tenancy.set_tenant_status(…)`, `tenancy.platform_audit_event` + `tenancy.record_platform_event(…)` | platform writes (append-only, D18 trigger) | V15002 |

**Per-table migration recipe** (D180 as amended, every S74 slice and S71/S72 for their modules): `ADD COLUMN tenant_id uuid NOT NULL DEFAULT '<bootstrap>'` → `SET DEFAULT shared.rls_tenant_id()` → `UNIQUE (tenant_id, id)` on parents first → business unique keys rebuilt as `(tenant_id, …)` (create new, drop old) → in-module foreign keys composite `NOT VALID` → `VALIDATE` → drop the single-column one → the helper for the table's class → registries. No `UPDATE`. Entities map `tenant_id` read-only or not at all.

**Flyway versions per slice** (next free on f32e4b5; re-checked when each slice branches):

| slice | versions |
|---|---|
| S71 | shared `V1010`, organization `V2005`, tenancy `V15001` |
| S72 | identity `V3016`, shared `V1011` (drops `spring_session*`, a new file in the shared directory) |
| S73 | identity `V3017`, organization `V2006`, production `V7012` |
| S74a | orders `V6031`, dining `V4008`, production `V7011` |
| S74b | settlement `V9061`, cash `V8012`, fiscal `V10013` |
| S74c | inventory `V13082`, customers `V11005` |
| S74d | catalog `V5009`, promotions `V12024`, reporting `V14007`, audit `V1107` |
| S75 | tenancy `V15002` |
| S76 | shared `V1012` (drops `rls_unscoped_table`), only if needed |

---

## S71 — Prefactor (runs alone, first)

Adds the tenant everywhere it is plumbing, and the organization module's tables, so that every later slice only converts its own module. Behaviour is unchanged for the bootstrap tenant.

**Write scope:** `backend/pom.xml`; `application.yaml` (main and test); `TestcontainersConfiguration` (+ the lazy shared Keycloak container bean); `config/persistence/**` (`TenantBindingTransactionManager`, advisor, startup check; removes `StoreScopedDataSource`, `StoreScopeDataSourcePostProcessor`); `shared/tenancy/**` (new); `shared/**` (idempotency rekey and its reads in a short transaction, `ChangeNotifier` on `pg_notify` keyed `(tenantId, storeId)`, `StoreProbe`, `StoreScopeViolations` + `RS003`); `shared/web/GlobalExceptionHandler.java`; `access/**` (`StoreScopes` reads the tenant, `Actor` idempotency scope with tenant, `@WithActor` binds the bootstrap tenant); `tenancy/**` (new module: model, `TenancyApi`, port + `UnavailableIdentityProviderAdmin`, events); `organization/**` (tenant columns, `company_profile` per tenant, `CompanyBootstrapRunner` binds the bootstrap tenant, the `TenantProvisioned` listener); every `*/model/event/**` record + its publish call (`tenantId` first); `@SystemScoped` scope changes (`IdempotencyRetention` → per tenant); migrations `shared/V1010`, `organization/V2005`, `tenancy/V15001`; `deploy/postgres/provision-roles.sql` (no grant on `tenancy`, role timeouts); `deploy/keycloak/realm-restaurantpro.json`, `realm-restaurantpro-ops.json` (minimal: realms, `restaurantpro-api` audience, `backoffice-bff`, LoA flow); tests under `src/test/**` for the above, `architecture/**`; `docs/architecture/backend-conventions.md` (every §5a row except Web and security). `pom.xml`: add the two dependencies above.

**Must not touch:** `identity/**`, `config/security/**`, any module's tables other than `shared`, `organization`, `tenancy`.

**Tests (red first):** `TenantBindingTransactionManagerTest`, `TenantScopesTest`, `JobLeaseTest` (two instances, latches), `ActorlessTenantScopeTest` (reach-ALL jobs iterate tenants, skip suspended, survive one failing; listeners bind the event's tenant; republished events bind theirs), `TenantIsolationTest` for organization (tenants A and B with identical store and terminal codes), `IdempotencyTenantScopeTest` (same key in two tenants → two records), `NotificationTenantIsolationTest`, `StoreProbeTenantTest` (other tenant → 404, other store → 403), `RowLevelSecurityCoverageTest` extended (`assertModuleTenantProtected(module)` + the `SECURITY DEFINER` allow-list + no app-role privilege on `tenancy`), `CompositeForeignKeyCoverageTest` (per-module mode), `TenantProvisioningChainTest` (organization half), ArchUnit `noTenantIdFromRequests`, `eventsCarryTenantId`, `cachesAreTenantScoped`, `onlyResolutionCreatesTenantContext` each with an `ArchitectureRulesFireTest` fixture; `TenantTestSupport` (tenants A and B, identical codes; `@StrictTenantScope`).

**Acceptance:** A82, A83 (transaction part), A84 (organization), A88, A90 (mechanism).

---

## Group 1 (after S71, in parallel): S72 ∥ S74a ∥ S74b ∥ S74c ∥ S74d ∥ S75

Every S74 slice: the migration recipe for each table of its modules; its module's `<Module>TenantIsolationTest` (A and B with identical codes: list, by id → 404, bulk report, insert with B's `tenant_id` → `42501`, update of `tenant_id` → `RS002`, child referencing B's parent → FK violation) calling `assertModuleTenantProtected`; its `<Module>RowLevelSecurityTest` green inside tenant A; its listeners and jobs added to `<Module>ActorlessScopeTest`; its autocommit JDBC moved into transactions; plan tests for its hot queries as the application role in tenant A with B's rows present (D189).

### S72 — Identity on Keycloak (top model)
**Write scope:** `identity/**`; `config/security/**` (`SecurityConfig`, `TenantResolutionFilter`, route families incl. `/platform/**` → ops issuer + `platform-operator`); `access/**` (`AccessPolicy` freshness from `acr`/`auth_time`, `Actor` from JWT + membership); `shared/web/GlobalExceptionHandler.java`; `bff/**` (new Spring Boot project: `oauth2-client`, token relay, `HttpOnly` cookie session, CSRF, `X-Tenant-Id` from its session, step-up endpoint; own `pom.xml`); `deploy/keycloak/**`; migrations `identity/V3016` (tenant columns, drop `password_hash` and `mfa_*`, OIDC link tables), `shared/V1011`; tests: the ~145 session/CSRF/login test files move to `jwt()`/`TestJwtFactory`; `backend-conventions.md` Web and security rows.
**Removes:** Spring Session, `SessionConfig`, CSRF on the API, `LoginSessions`, `LoginService.passwordLogin`, `PasswordService` (BackOffice), `identity.mfa.*`, `Mfa*` services/controllers/filters, `identity.oidc.*`, `LoginThrottling`, `SupportGrantExpiry`, `RemoteAddresses`, `AdminBootstrapService`, the `RESOLVING_ACTOR` fallback.
**Tests:** `JwtValidationTest`, `TenantResolutionTest`, `StepUpTest`, `SupportAccessTest` (ops issuer only for `SUPPORT`, ≤ 24 h, audited, admin notified), `TenantListTest` (`GET /auth/tenants`), `IdpUnavailableTest` (starts with Keycloak down → 503), `BffTokenRelayTest`, `TenantIsolationTest` for identity, `TenantProvisioningChainTest` (identity half, port faked), `KeycloakLoginIT` (Keycloak Testcontainer). **Acceptance:** A81, A85, A87, A84 (identity).

### S74a — Orders, dining, production (mid tier)
**Write scope:** `orders/`, `dining/`, `production/` (repositories, probes, tests); `orders/V6031`, `dining/V4008`, `production/V7011`. **Tests:** `OrdersTenantIsolationTest`, `DiningTenantIsolationTest`, `ProductionTenantIsolationTest` + the module suites. **Acceptance:** A80, A82, A84 (their tables).

### S74b — Settlement, cash, fiscal (mid tier; fiscal reviewed by the top model)
**Write scope:** `settlement/`, `cash/`, `fiscal/`; `settlement/V9061`, `cash/V8012`, `fiscal/V10013`. Fiscal series and number uniqueness become `(tenant_id, …)`; the immutability triggers and the R3 lock order stay green unchanged. **Tests:** `SettlementTenantIsolationTest`, `CashTenantIsolationTest`, `FiscalTenantIsolationTest` (same series code in A and B issue independent numbers). **Acceptance:** A80, A82, A84.

### S74c — Inventory, customers (mid tier)
**Write scope:** `inventory/`, `customers/`; `inventory/V13082` (+ tenant-aware `stock_transfer_stores`), `customers/V11005`. **Tests:** `InventoryTenantIsolationTest` (a transfer never crosses tenants), `CustomersTenantIsolationTest`; the D132 deadlock ITs stay green. **Acceptance:** A80, A82, A84.

### S74d — Catalog, promotions, reporting, audit (mid tier)
**Write scope:** `catalog/`, `promotions/`, `reporting/` (`ReleaseFamilies` keyed `(tenantId, releaseId)`, `RebuildRunner` in transactions), `audit/`; `catalog/V5009`, `promotions/V12024`, `reporting/V14007`, `audit/V1107`. **Tests:** `CatalogTenantIsolationTest` (same item code and barcode in A and B), `PromotionsTenantIsolationTest`, `ReportingTenantIsolationTest`, `AuditTenantIsolationTest`, `TenantCacheIsolationTest`, `RebuildRunnerTenantTest`. **Acceptance:** A80, A82, A84.

### S75 — Platform and deploy (top model)
**Write scope:** `tenancy/**` (platform controllers `/api/v1/platform/tenants` create/suspend/reactivate/close, the `RestClient` `IdentityProviderAdmin` replacing the placeholder, `V15002`); `deploy/**` except `deploy/keycloak/**` and `provision-roles.sql` (Keycloak 26.x with its own PostgreSQL in the dev Compose, `deploy/tenant-export.sh`, the tenant-restore drill, `deploy/drills/pgbouncer/**`, the B8 spike notes `deploy/drills/b8-rds/README.md`), `backend/compose.yaml`, `backend/run-local.sh`.
**Tests:** `PlatformTenantLifecycleTest` (onboarding idempotent end to end with the event chain; suspend → next request 403 and jobs skip), `PlatformAuthorizationTest` (tenant-realm token → 403; no business data reachable), `IdentityProviderAdminIT` (Keycloak Testcontainer; the provisioner cannot change realm settings — O1 evidence), `PgBouncerTransactionModeTest` (drill), `deploy/test-tenant-export.sh`, `deploy/test-tenant-restore.sh` (both under D188 (a); (b) too if B8 says so). **Acceptance:** A83 (PgBouncer), A91, A90 (suspension).

---

## Group 2 (after S72, S74a and S75): S73 — Devices (top model)

**Write scope:** `identity/**` (enrolment, POS session bound to the terminal membership, Keycloak session listing/logout endpoints), `organization/**` (terminal client id, drop `device_token_hash`), `production/**` (`PrintWorker` → dispatcher, `/api/v1/bridge/jobs` claim with lease and `SKIP LOCKED`, outcome, bridge enrolment, `BRIDGE` membership bound to one store), `access/**` (`PosContext` from `X-Pos-Session`); `identity/V3017`, `organization/V2006`, `production/V7012`; `bridge/**` (new Java agent project, own `pom.xml`: long-poll client, ESC/POS over TCP 9100 reusing the D154–D158 renderer contract, service packaging).
**Stop and ask** if the O1 evidence shows the provisioner cannot be confined to `pos-*`/`bridge-*` clients.
**Tests:** `PosTerminalAuthTest`, `TerminalEnrolmentThrottleTest`, `BridgeJobClaimTest` (store S's bridge cannot claim another store's or tenant's jobs), `BridgeOutcomeTest`, `TerminalRevocationTest` (next request refused). **Acceptance:** A86, A89.

## S76 — Integrate (alone, after every slice has merged)

**Write scope:** `src/test/.../e2e`; `RowLevelSecurityCoverageTest` and `CompositeForeignKeyCoverageTest` strict over all schemas; removal of `CompanyBootstrapRunner`'s pre-SaaS path; `shared/V1012` if needed; `CONTEXT.md`; ADR 0040 → Accepted, ADR README; `docs/architecture/modules/`; this PRD and the decision doc's amendment markers; the load plan.
**Deliverables:** `R6AcceptanceTest` over HTTP with real security: tenants A and B with identical codes, a POS of each, a manager of each, an accountant with both memberships, support with a grant in A; every route and report shows only the caller's tenant; foreign ids → 404, other store → 403; a listener chain completes under the event's tenant; suspension of B mid-test. Full suite (A88); load smoke at 20 tenants × 10 terminals with a no-RLS baseline first (A92, carrying RLS A77); every drill once on the integrated tree. **Acceptance:** A80–A92 end to end. Then the architecture review (G6).

---

## Blocked-by graph and parallel groups

```
R6 planning PR merged
 └─► S71 Prefactor (alone)
      ├─► S72 Identity on Keycloak ───────────┬─► S73 Devices ─┐
      ├─► S74a Orders, dining, production ────┤                │
      ├─► S75 Platform and deploy ────────────┘                │
      ├─► S74b Settlement, cash, fiscal ───────────────────────┤
      ├─► S74c Inventory, customers ───────────────────────────┤
      └─► S74d Catalog, promotions, reporting, audit ──────────┴─► S76 Integrate (alone) ─► G6
```

S73 is blocked by **S72** (identity, access), **S74a** (production) and **S75** (the real `IdentityProviderAdmin`), so no two parallel slices share a write path. This extends the brief's `S72 → S73` edge; recorded in RUN.md.

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S71 alone | every hotspot, `pom.xml`, `application.yaml`, harness, events, `organization`, `tenancy` (new), `shared` |
| 1 | S72 ∥ S74a ∥ S74b ∥ S74c ∥ S74d ∥ S75 | `identity`+`config/security`+`access`+`bff`+`deploy/keycloak`+V3016/V1011; `orders`+`dining`+`production`+V6031/V4008/V7011; `settlement`+`cash`+`fiscal`+V9061/V8012/V10013; `inventory`+`customers`+V13082/V11005; `catalog`+`promotions`+`reporting`+`audit`+V5009/V12024/V14007/V1107; `tenancy`+`deploy` (rest)+V15002. Tests only in their own module packages. |
| 2 | S73 alone | `identity`, `organization`, `production`, `access`, `bridge`; V3017/V2006/V7012 |
| 3 | S76 alone | e2e, strict coverage, docs, ADR status, load |

## Acceptance criteria → slices

| criterion (multitenancy-decisions §4) | slices |
|---|---|
| A80 | S74a–d, S76 |
| A81 | S72, S76 |
| A82 | S71, S74a–d, S76 |
| A83 | S71 (transaction), S75 (PgBouncer), S76 |
| A84 | S71, S72, S74a–d, S76 |
| A85 | S72 |
| A86 | S73 |
| A87 | S72 |
| A88 | every slice, S76 |
| A89 | S73 |
| A90 | S71 (mechanism), S75 (suspension), S76 |
| A91 | S75 |
| A92 | S76 |

## Testing decisions

- **Seams (highest first, existing preferred):** (1) the HTTP API through MockMvc with `jwt()`/`TestJwtFactory` and, in `e2e`, the real server — for A80–A82, A85, A87; (2) the application-role JDBC seam `AppRoleSession` (RLS run) extended with `tenant(…)` — for A84, database isolation without `AccessPolicy`; (3) the Keycloak Testcontainer, only in identity and e2e suites; (4) the deploy drill scripts — for A83 (PgBouncer) and A91. No new seam beyond `TenantTestSupport` and `TestJwtFactory`.
- A good test asserts observable behaviour: HTTP status and body, rows visible to the application role, SQLSTATEs, events delivered. It never asserts that a filter or helper was called.
- Every rule added must turn a test red when removed or weakened (R4 mutation rule); reviewers run the mutants.
- Prior art: `RlsAcceptanceTest`, `<Module>RowLevelSecurityTest`, `<Module>ActorlessScopeTest`, `RowLevelSecurityCoverageTest`, `AppRoleSession`, `@WithActor`, `StoreScopeTestSupport.inStrictFixture`, `ArchitectureRulesFireTest`.
- Existing tests keep running in the bootstrap tenant through the fixture fallback (D189 as amended); only the isolation suites use `@StrictTenantScope`.
- Run-time budget: the full gate grows at most 20 % over the RLS close; S76 records it.

## Model routing and review

Security-critical slices (S71, S72, S73, S75) and every review use the top model; the repetitive per-module conversions (S74a–d) use a mid tier, with S74b's fiscal migration and every G5 reviewed by the top model. Each slice gets a fresh code reviewer who never saw the work. Every review checks the **10 security rules**:

1. No tenant from a request: no `tenantId` in a controller parameter, request DTO, path or query; `X-Tenant-Id` only selects among memberships.
2. Every tenant table: `tenant_id NOT NULL DEFAULT shared.rls_tenant_id()`, restrictive `tenant_scope` with `FORCE`, `UNIQUE (tenant_id, id)`, `tenant_fixed`.
3. Every in-module foreign key and business unique key includes `tenant_id`.
4. No protected-table access outside a transaction; no `set_config(…, false)`; no session-level setting anywhere.
5. Cross-tenant id → 404 with no existence hint; same-tenant other store → 403; one `TENANT_FORBIDDEN` body for foreign and random tenants.
6. Every event carries `tenantId`; every listener and job binds a tenant before its transaction; no cross-tenant scope for the application role.
7. Every cache, SSE key, idempotency scope, log MDC and object-storage key includes the tenant; no `tenant_id` metric label.
8. JWT validation complete and fail closed (alg allow-list, `iss`, `aud`, `azp` per route family, `typ`, time claims); claims never grant authority.
9. `SECURITY DEFINER` functions only from the allow-list, each with `search_path` pinned, `EXECUTE` not granted to `PUBLIC`, and a test.
10. No secret, token, PIN, private key or enrolment code in logs, audit details, idempotency records or the PR.

**Stop and ask the user** when: a slice needs a write path or Flyway version outside its scope; a security rule above cannot be met as specified; O1 shows the provisioner cannot be confined; B8 rules out both D188 (a) and (b); a migration would `UPDATE` an immutable row or rewrite a large table; a test can only pass by weakening an assertion or adding `@Disabled`; the full suite is red for a cause outside the slice; or a change would alter a decision marked ★.

## Out of scope

Tenant plans, billing and subscriptions; federation of a tenant's own IdP; partitioning by tenant; offline POS writing (§11); the POS and BackOffice SPA changes (enrolment screen, WebCrypto keys, token renewal, `X-Pos-Session`, tenant switcher, step-up retry) — a frontend run; tenant self-service export UI; production AWS provisioning (IaC), Keycloak HA hosting (O3) and data residency (O4); the P70 import tool (no live installation).

## Risks

- **S71 is large** (every event record, the transaction manager, `organization`, `tenancy`, harness). Mitigation: it runs alone, red-first per contract, and keeps behaviour unchanged for the bootstrap tenant.
- **S72 test churn** (~145 files). Mitigation: `TestJwtFactory` and `@WithActor` absorb most; the identity suites use Keycloak only where login itself is under test.
- **O1/B8** may force a design change mid-run; both are "stop and ask".
- **Performance** (A92 carries RLS A77, p95 701–962 ms on one route): a no-RLS baseline is measured before any optimisation.
- **Event signature change** in S71 touches every module's events and publishers once; listeners in S74 slices read `tenantId` from them.

## Rollback

Forward-only Flyway. Before the pilot (P70 = none) rollback is **restore the pre-upgrade backup**: after V1010 the `tenant_id` defaults call `rls_tenant_id()`, so an older image that binds no tenant cannot insert. A slice PR that breaks `main` is reverted before the next migration that depends on it merges; a merged migration is never edited, only followed by a new one.
