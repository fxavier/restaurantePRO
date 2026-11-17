# restaurantPRO backend — SaaS multi-tenancy (shared schema, `tenant_id`, RLS, Keycloak): decisions for approval

Status: **APPROVED** 2026-11-15 by Xavier Francisco ("aprovado", R6 G1), with the R6 G1 amendments below and open items O1–O5 (§5a). Proposed 2026-11-15. This document continues [backend-decisions.md](backend-decisions.md) (D1–D33), [r2-decisions.md](r2-decisions.md) (D34–D63), [r3-decisions.md](r3-decisions.md) (D64–D98), [r4-decisions.md](r4-decisions.md) (D99–D136), [r5-decisions.md](r5-decisions.md) (D137–D161) and [rls-decisions.md](rls-decisions.md) (D162–D173, P61–P68, A69–A79). Those decisions stay in force unless a decision below supersedes or amends one by id. Ids continue from **D174, P69 and A80**. Once approved, D174 ★, D177 ★, D178 ★ and D179 ★ are recorded in [ADR 0040](../adr/0040-saas-multitenancy-tenant-id-rls-keycloak.md) (written now with status *Proposed*).

Source of truth: [product_description.md](../product_description.md), **amended on 2026-11-15** in §1, §2, §10 and §11 (cited as §n). Glossary: [CONTEXT.md](../../CONTEXT.md). Coding rules: [backend-conventions.md](../architecture/backend-conventions.md). Baseline: `main` at 0b5a000 (R5 + RLS S64 + S65 merged). *(Amended 2026-11-15, R6 G1: re-baselined on `main` f32e4b5, the whole RLS run S64–S70 merged, suite 3354/0/0/0.)*

**User decision (binding, 2026-11-15).** This reverses the decision of 2026-11-13 recorded in rls-decisions.md ("no `tenant_id`, no Keycloak, no spec change"). The user's choices:

1. **Multi-tenancy with a shared schema, a `tenant_id` column and PostgreSQL RLS.** In the user's words, it gives the best balance of cost, operational complexity and scalability. Isolation needs controls in both the application and the database, not only filters in repositories.
2. **Keycloak is the identity provider.** The backend validates the JWT and resolves the tenant from an authorised user–company association. A `tenant_id` received in a request is never accepted without validation. The tenant identity is set in the transactional context and applied to the PostgreSQL connection with `SET LOCAL` before any access to protected data.
3. **SaaS only.** Every company runs on the central installation. The local, offline-first server per company (§2, §11 before the amendment) is dropped.
4. **Keep the store-level RLS (D162–D173) and combine it with the tenant RLS.** The tenant is the hard boundary; the store scope stays as defence in depth inside a tenant.
5. **Each enrolled POS terminal is a Keycloak confidential client.** The backend still verifies the employee PIN, the lockout and the approver PIN locally.
6. **Record the change by superseding, not by rewriting.** This document, "superseded" markers in r1–r5 and rls, ADR 0040, and an amendment of the spec and CLAUDE.md.

---

## 0. Blockers and questions that need you

**Blockers**

| id | blocker | proposal |
|---|---|---|
| B6 | No Keycloak exists yet: no realm, no clients, no hosting decision. | S75 adds a pinned Keycloak 26.x to the development Compose file and to the Testcontainers harness. The production topology (D175) needs an AWS account and region decision (P75). |
| B7 | Whether any customer installation is live decides the migration path (D180). | P70. |
| B8 | Before RDS/Aurora is chosen for production, confirm that the managed PostgreSQL allows a non-superuser **`BYPASSRLS`** owner role (D163) and `FORCE ROW LEVEL SECURITY`. | A short spike in S75. If it is not allowed, the fallback is D188 (b). |

**Questions only you can answer.** Each has my recommendation; the run is built with it unless you override it.

| id | question | recommendation (default) | what changes if you choose otherwise |
|---|---|---|---|
| P69 | How the BackOffice browser app holds tokens. | **A thin BFF** (Spring Boot with `oauth2-client` and token relay, same origin as the SPA, `HttpOnly` cookie towards the browser, no token in JavaScript). The backend stays a pure resource server that validates the JWT, as you asked. The OAuth browser-based-apps BCP draft rates the BFF the strongest pattern for apps that hold sensitive authority (fiscal, prices, users). | **Public client with PKCE in the browser.** One component fewer, but the access token lives in JavaScript memory, so an XSS reaches it. The SPA would need strict CSP, short tokens and refresh-token rotation. |
| P70 | Is any customer installation in production today? | **None, pre-pilot.** Existing development databases are migrated in place to a single *bootstrap tenant* (D180). | **Yes.** Each live installation is imported as a new tenant with a dedicated export/import tool (its own slice), run per installation in a maintenance window. |
| P71 | Keycloak realm topology. | **Two realms:** `restaurantpro` holds every tenant's users, with Keycloak **Organizations** (fully supported since 26.0) as the tenant directory. `restaurantpro-ops` holds platform operators and support staff. | **One realm per tenant:** hard isolation in Keycloak, but realm count grows with tenants, which degrades the admin and the server, and onboarding means creating a realm. |
| P72 | Choosing the tenant when a subject belongs to several tenants (an accountant, a group owner, support). | **An `X-Tenant-Id` request header, validated against the subject's active memberships** (D177). Without it, a subject with several memberships gets 400. | **A token scoped to one organization** (`scope=organization:<alias>`): the tenant is fixed per token and switching means a new token. The membership is still validated; the header is not needed. |
| P73 | Membership lookups per request. | **Cache for at most 30 s**, evicted at once by the tenancy events (membership revoked, tenant suspended). | **No cache:** one query per request (an indexed primary-key probe), and revocation takes effect immediately. |
| P74 | Tenant label on Prometheus metrics. | **No `tenant_id` label** (cardinality grows with tenants). Per-tenant health goes through the in-app alerts (D149), computed per tenant. | **A label on a few business counters:** per-tenant dashboards, with a cardinality budget to watch. |
| P75 | AWS region (latency to Mozambique, data residency). | **`af-south-1` (Cape Town)**, the AWS region closest to Maputo. Confirm whether the target customers need in-country data residency, which no AWS region offers. | **An EU region** (for example `eu-west-1`): more services and lower cost, but higher latency to Mozambican POS terminals. |

*(Answered 2026-11-15, R6 G1: the user accepted every recommendation. **P69: BFF.** **P70: none, pre-pilot** — B7 closed; in-place migration to one bootstrap tenant, no import tool. **P71: two realms** (`restaurantpro` with Organizations, `restaurantpro-ops`). **P72: `X-Tenant-Id` header** validated against memberships. **P73: cache ≤ 30 s** with eviction — see the D177 amendment for several instances. **P74: no `tenant_id` metric label.** **P75: `af-south-1`**; in-country residency stays an open product question for the first contract, not a blocker for R6.)*

*(B8 spike, R6 G1 — proposed, not run; owned by S75. On a throw-away RDS for PostgreSQL 18 instance, and on Aurora PostgreSQL if 18 is offered: (1) as the master user (`rds_superuser`), `CREATE ROLE r6_owner LOGIN BYPASSRLS` and `ALTER ROLE r6_owner BYPASSRLS`, recording the exact error if refused; (2) as a plain owner, `ALTER TABLE t ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY` and a restrictive policy; (3) run `deploy/postgres/provision-roles.sql` and V1008–V1010 under Flyway as `r6_owner`; (4) the `RowLevelSecurityCoverageTest` queries against `pg_policies`/`pg_class`. Outcome recorded in D188: (a) kept if (1) succeeds, otherwise fallback (b). Cost: one small instance for under an hour. B6 stays open until S75: the dev Compose and Testcontainers Keycloak are built there; production Keycloak hosting follows P75.)*

---

## 1. Scope

| area | IN | out |
|---|---|---|
| Tenancy model | `tenant_id` on every tenant-owned table, composite keys, a `tenancy` module, tenant lifecycle (onboarding, suspension, closure), the platform API for operators | Tenant plans, billing and subscriptions (a product decision, §15) |
| Identity | Keycloak as the IdP, JWT resource server, tenant resolution from memberships, step-up via `acr`/`auth_time` (RFC 9470), terminals and print bridges as confidential clients, support access across tenants | Federation of each tenant's own IdP into Keycloak (possible through Organizations; a later decision) |
| Database | `SET LOCAL` binding of tenant and store scope at transaction begin, a restrictive tenant policy combined with the D168 store policies, tenant-aware probes and helpers | Partitioning by tenant, a schema or database per tenant |
| Non-database isolation | Cache keys, SSE topics, idempotency scopes, object storage prefixes, logs, per-tenant secrets, per-tenant rate limits | |
| Devices | A print bridge on the store LAN (printers and cash drawer reached through it) | Offline POS writing and later synchronization (still out of scope, §11) |
| Operations | Keycloak HA, horizontal scaling of the backend, PITR for the whole cluster, per-tenant logical export | Tenant self-service export UI |

**Module placement.** One new module, **`tenancy`** (`shared`, `audit`). `identity` depends on `tenancy` (memberships); `config` uses it for the request pipeline. No business module depends on `tenancy`: modules see the tenant only through `shared.tenancy.TenantContext` (an id and its origin, technical only, D14). D13 gains these edges: `tenancy → shared, audit`; `identity → tenancy`.

---

## 2. Decisions

<a id="d174"></a>
### D174 ★ — The tenancy model: one tenant per company, shared schema, `tenant_id` everywhere

**Supersedes** spec §2 before the amendment, D30 (singleton company bootstrap), P6 (local server per company), and the "one installation per company" premise of D32/D98, D46, D138 and rls §0.

- **Tenant = company (Empresa).** One legal entity, one fiscal profile, one or more stores. `tenancy.tenant(id uuid PK, code text UNIQUE, legal_name, status, created_at, suspended_at, closed_at, version)` with status `PROVISIONING → ACTIVE ⇄ SUSPENDED → CLOSED`.
- **Shared database, shared schemas.** The module schemas (D6) stay; they organize modules, not companies.
- **Every tenant-owned table gets `tenant_id uuid NOT NULL`**, children included. Each table then carries its own tenant predicate with no parent lookup, and composite foreign keys can include the tenant (D179). Exceptions are only the **PLATFORM** tables (D179): `tenancy.*`, `flyway_schema_history`, `event_publication*`.
- **Uniqueness is per tenant.** Every business unique key becomes `(tenant_id, …)`: store code, terminal code, item code, barcode, fiscal series and number, employee code, username, `(scope, key)` of idempotency. Two tenants can then use the same codes, and a unique violation cannot reveal another tenant's data.
- **Primary keys stay `id uuid` (UUIDv7, D7), globally unique.** Every table also gets `UNIQUE (tenant_id, id)`, the target of composite foreign keys. Routes and DTOs keep plain ids.
- **The company profile becomes per tenant:** `organization.company_profile` loses the single-row constraint (`id boolean PRIMARY KEY CHECK (id)`, D30) and gets `UNIQUE (tenant_id)`. Country, currency and fiscal regime are per tenant (P1 and P2 stay configurable, now per tenant).

**Alternatives considered.** (a) Schema per tenant: strong isolation, but Flyway runs per schema, connection `search_path` handling and N× catalog growth. Rejected by the user for cost and operational complexity. (b) Database per tenant: the strongest isolation, highest cost, and the old per-installation operations at SaaS scale. (c) `tenant_id` filters in repositories only: one forgotten filter crosses companies. Rejected; RLS is mandatory.

<a id="d175"></a>
### D175 ★ — Keycloak is the core identity provider

**Supersedes** D138 and D144 (ADR 0034), the BackOffice password part of D26, D139–D141 and D143 (native TOTP, ADR 0035), and D145. **Amends** D142 (D184). *(Amended 2026-11-15, R6 G1, from the code: superseding D144 removes the existing OIDC federation code in `identity.oidc` (`ExternalIdentityService`, `LazyClientRegistrationRepository`, `PendingOidcLink`, the link tables) in S72, together with `LoginSessions`, `LoginService.passwordLogin`, `PasswordService`, `user_account.password_hash`, `identity.mfa.*` and the `Mfa*` services and filters; the D142 freshness check `AccessPolicy.requireFreshSecondFactor` reads `acr`/`auth_time` from the JWT instead of the session (D184). The device token (`X-Terminal-Token`, checked only at POS login today) is removed with D182.)*

- **Version:** Keycloak **26.x, pinned** (minor upgrades are a planned change, like PostgreSQL in D4). Same image in development, tests and production.
- **Realms (P71):** `restaurantpro` (tenant users; **Organizations enabled**; one organization per tenant, `alias = tenant.code`, its id stored in `tenancy.tenant.keycloak_org_id`). `restaurantpro-ops` (platform operators and support; never holds tenant users).
- **Keycloak owns:** credentials and the password policy, brute-force detection, MFA (OTP and WebAuthn), step-up flows with levels of authentication (LoA), sessions and logout, required actions (verify email, update password). The local `identity.user_account` keeps the **profile, roles × stores and status**, with no password hash and no TOTP secret.
- **Keycloak does not own authority.** Groups, client roles and organization membership claims are never mapped to permissions (as D144 already argued). Local RBAC per store (D27) stays the only authority inside a tenant. The tenant itself comes from the database membership (D177), not from a claim.
- **Clients:**

| client | type | used by | notes |
|---|---|---|---|
| `restaurantpro-api` | bearer-only (audience) | the backend | audience mapper adds it to every access token |
| `backoffice-bff` | confidential, auth code + PKCE `S256` | the BFF (P69) | `private_key_jwt`; redirect URIs exact |
| `pos-<terminalId>` | confidential, client credentials, `private_key_jwt` (RFC 7523) | one enrolled POS terminal | D182 |
| `bridge-<bridgeId>` | as above | one print bridge | D187 |
| `tenancy-provisioner` | confidential, service account | the backend's provisioning adapter only | Keycloak fine-grained admin permissions: manage organizations, members and `pos-*`/`bridge-*` clients only, never realm settings |

- **Token lifetimes:** access token **5 min**; BFF refresh token rotated on every use (reuse detected → session revoked); SSO idle 30 min, maximum 10 h (a shift). Terminal tokens: client credentials, no refresh token, renewed before expiry.
- **Deployment (production):** at least two Keycloak nodes behind the load balancer, with **its own PostgreSQL** (separate from the application database, separate backups). Hostname `auth.<domain>`, TLS only, admin console reachable only from the operators' network.

**Alternatives considered.** (a) Keep local identity (D138): rejected by the user; in SaaS, the per-installation cost argument of D138 (1) and (2) no longer holds. (b) A managed IdP (Cognito, Entra External ID): fewer operations, but vendor lock-in, and Cognito's tenancy and step-up support is weaker. Keycloak also matches the user's stack.

<a id="d176"></a>
### D176 ★ — The backend is an OAuth2 resource server; server-side sessions go away

**Supersedes** D25 (Spring Session JDBC, CSRF, cookie) and ADR 0007. **Amends** D24/D47 (the idempotency scope, D186).

- **Dependency:** `spring-boot-starter-oauth2-resource-server` (Nimbus). `spring-session-jdbc` is removed; the `spring_session*` tables are dropped by migration once no backend uses them.
- **JWT validation, all mandatory (fail closed):**
  - signature against the realm's JWKS; **algorithm allow-list `RS256`, `ES256`** (no `none`, no `HS*`). JWKS is cached and refreshed on an unknown `kid`, at most once per 30 s;
  - `iss` equals exactly the configured issuer, one per realm (`restaurantpro` for tenant users, terminals and bridges; `restaurantpro-ops` only on `/api/v1/platform/**` and for support, D183);
  - `aud` contains `restaurantpro-api`; `exp`, `nbf` and `iat` with a 30 s clock skew;
  - `azp` is in the allow-list for the route family: `backoffice-bff` → `/backoffice/**` and `/auth/**`; `pos-*` → `/pos/**`; `bridge-*` → `/bridge/**`;
  - `typ` claim `Bearer` (Keycloak), so an ID token or a refresh token is never accepted as an access token.
- **Startup does not depend on Keycloak:** the decoder fetches JWKS lazily (the D144 lesson), and requests answer 503 `IDP_UNAVAILABLE` while JWKS cannot be fetched and no key is cached.
- **CSRF:** with bearer tokens in `Authorization`, the API needs no CSRF token. The BFF (P69) keeps CSRF protection on its cookie session.
- **Session listing and revocation (§5)** go through the Keycloak Admin API (user sessions); "end all sessions of an account" also disables the membership (D177), so the next request is refused within the cache window (P73).

<a id="d177"></a>
### D177 ★ — Tenant resolution: from an authorised membership, never from the request alone

**Tables** (schema `tenancy`, PLATFORM class, D179):

```
tenancy.tenant_membership(
  id uuid PK, tenant_id uuid NOT NULL REFERENCES tenancy.tenant,
  issuer text NOT NULL, subject text NOT NULL,          -- (iss, sub) of the JWT
  kind text NOT NULL CHECK (kind IN ('ACCOUNT','TERMINAL','BRIDGE','SUPPORT')),
  principal_id uuid NOT NULL,                           -- user_account / terminal / bridge / support grant id
  status text NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
  valid_from timestamptz NOT NULL, valid_until timestamptz,   -- required for SUPPORT
  created_at, revoked_at, version,
  UNIQUE (issuer, subject, tenant_id))
```

**The application role has no privilege on `tenancy.*`.** It reads memberships only through one `SECURITY DEFINER` function (owner, `SET search_path = pg_catalog`):

```
tenancy.resolve_membership(p_issuer text, p_subject text, p_requested uuid)
  RETURNS TABLE (tenant_id uuid, kind text, principal_id uuid, tenant_status text)
```

It returns at most the rows that are `ACTIVE`, valid at `now()`, for that `(iss, sub)`, and only the `p_requested` tenant when one is given. It never lists tenants the subject does not belong to.

**Algorithm** (`config.security.TenantResolutionFilter`, after `BearerTokenAuthenticationFilter`, before any controller):

1. The JWT is valid (D176), otherwise 401.
2. `requested = X-Tenant-Id` header, when present. It must be a well-formed UUID (400 otherwise). It is a **selection, never an assertion** (P72).
3. `rows = resolve_membership(iss, sub, requested)`. The result is cached per `(iss, sub, requested)` for at most 30 s (P73).
4. No row → **403 `TENANT_FORBIDDEN`**. The same answer is given whether the tenant does not exist or the subject is not a member, so there is no oracle.
5. Several rows and no header → **400 `TENANT_SELECTION_REQUIRED`**, listing nothing. The client offers the choice from `GET /api/v1/auth/tenants`, which lists only the caller's own memberships.
6. Exactly one row with `tenant_status ≠ ACTIVE` → 403 `TENANT_SUSPENDED` (or `TENANT_CLOSED`).
7. Otherwise `TenantContext(tenantId, membershipKind, principalId, origin)` is created, immutable, and bound to the request thread. The `Actor` (D38) is then built from that tenant's local identity rows (`user_account`, roles × stores, terminal, POS session) **inside a tenant-bound transaction (D178)**, so RLS applies to the Actor lookup itself.
8. **The `organization` claim** (when present) is compared with the resolved tenant's `keycloak_org_id`. A mismatch is logged and counted (`tenant_claim_mismatch_total`) and **never grants or changes anything**. A claim can outlive a configuration change (for example CVE-2026-9791: organization data still in tokens after the feature was disabled).

**Never from the body, the path or the query.** ArchUnit rules: no controller parameter and no request DTO component named `tenantId`. `TenantContext` has no public constructor outside `config.security` and the test support. POS and bridge requests never send `X-Tenant-Id`: their membership is unique (`kind TERMINAL` and `BRIDGE` have exactly one tenant).

**Alternatives considered.** (a) Trust a `tenant_id` claim set by a Keycloak mapper: the IdP becomes a second authority, and a realm misconfiguration crosses tenants. (b) A subdomain per tenant (`acme.app…`): good UX, but it is still only a selection that needs the same membership check; it can be added later on top of this.

*(Amended 2026-11-15, R6 G1 — recommended answers accepted by the user ("use recommended answers"):*
- ***P73 amended: no membership cache.** Spring Modulith events reach only the publishing instance, so with several backend instances (D185) an in-process cache keeps a revoked membership or a suspended tenant for the full 30 s on every other instance. Step 3 therefore calls `resolve_membership` on every request (one `SECURITY DEFINER` primary-key probe inside the request's own transaction); revocation and suspension take effect on the next request everywhere. A cache, if A92 ever shows the need, is a later decision with cross-instance eviction (LISTEN/NOTIFY). This removes the cache key and eviction questions, and every "within the cache window" in D176, D182 and §3 reads "on the next request".*
- ***Issuer rule in SQL.** `resolve_membership` gains a fourth parameter, `p_kinds text[]`. The filter passes `{ACCOUNT,TERMINAL,BRIDGE}` for the `restaurantpro` issuer and `{SUPPORT}` for the `restaurantpro-ops` issuer; any other issuer is already refused by D176. A table check enforces `kind = 'SUPPORT'` ⇒ `valid_until IS NOT NULL` and `valid_until - valid_from ≤ 24 h` (D183 amended).*
- ***Multi-membership UX.** `GET /api/v1/auth/tenants` returns the caller's own active memberships (`tenantId`, `code`, `legalName`, `kind`) through the same function with `p_requested = NULL`; it never needs `X-Tenant-Id`. The BFF keeps the selected tenant in its own session and adds `X-Tenant-Id` to every relayed call; the SPA switches tenant through the BFF. A single membership needs no choice.)*

<a id="d178"></a>
### D178 ★ — Binding at transaction begin with `SET LOCAL` semantics

**Supersedes** D165 (the checkout hook `StoreScopedDataSource`), choosing the P62 alternative. **Amends** D164 (new variable, transaction-local values).

**Variables** (all transaction-local):

| setting | value |
|---|---|
| `app.tenant_id` | the resolved tenant (`uuid` text), or `''` |
| `app.all_stores`, `app.store_ids`, `app.scope_origin` | as D164, within the tenant |

`shared.rls_tenant_id()` (`STABLE PARALLEL SAFE`) returns `app.tenant_id::uuid` and **raises `RS001` when it is unset or empty** (P63 unchanged). The store functions of D164 are unchanged.

**Mechanism.** `config.persistence.TenantBindingTransactionManager extends JpaTransactionManager` overrides `doBegin`: it calls `super.doBegin`, takes the transaction's bound JDBC connection (`DataSourceUtils.getConnection(dataSource)`, the same `ConnectionHolder` that JPA and `JdbcClient` use), and runs one statement:

```sql
SELECT set_config('app.tenant_id',   ?, true),
       set_config('app.all_stores',  ?, true),
       set_config('app.store_ids',   ?, true),
       set_config('app.scope_origin',?, true)
```

- **`set_config(…, true)` is `SET LOCAL`** with bind parameters. A literal `SET LOCAL app.tenant_id = '…'` does not accept parameters, so it would need string building, which is an injection risk. The semantics are the same: the value lasts until the transaction commits or rolls back.
- **On failure** the transaction is rolled back and `CannotCreateTransactionException` is thrown. No statement of the caller ever runs unbound.
- **Why not `TransactionExecutionListener.afterBegin`:** Spring documents that contract as stateless and "primarily meant for observation and statistics", so a listener is the wrong place for a security control.
- **Propagation:** `REQUIRED` joins the outer transaction, which is already bound. `REQUIRES_NEW` (`PinVerification`, every `@ApplicationModuleListener`) begins a new transaction, which is bound from the thread's current context. `NESTED` uses a savepoint on the same bound connection. `readOnly` transactions are bound the same way, because `set_config` is allowed in a read-only transaction.
- **Outside a transaction there is no tenant, so access fails closed:** an autocommit statement on a protected table raises `RS001`. Spring Data repository methods are transactional by default, so they are covered. Hand-written `JdbcClient` calls outside a transaction must move into one, and the full suite under the application role finds them (A88).
- **A context cannot change inside a transaction:** `TenantScopes.run(…)` throws `IllegalStateException` when a transaction is active (as D165).
- **No leak across pooled connections:** the values end with the transaction, so a returned connection carries nothing. This also makes the binding correct behind **PgBouncer in transaction pooling mode** (a session-level `set_config` would not be), which matters for SaaS connection scaling (D188).
- **Cost:** one round trip per transaction, inside the same connection.

*(Amended 2026-11-15, R6 G1, from the code on f32e4b5 (spring-orm 7.0.10, Hibernate 7.4.13):*
- *`JpaTransactionManager.doBegin` always binds a `ConnectionHolder` (a `HibernateConnectionHandle`) keyed by the EMF's `DataSource`, and Hibernate acquires the physical connection at begin (`provider_disables_autocommit` is not set). `DataSourceUtils.getConnection(dataSource)` therefore returns the transaction's connection; the mechanism holds.*
- *There is one transaction manager today, Boot's auto-configured bean (`@ConditionalOnMissingBean`). `TenantBindingTransactionManager` replaces it as the only `PlatformTransactionManager` bean and applies `TransactionManagerCustomizers` itself. The 14 services that build `new TransactionTemplate(transactionManager)` and Boot's `TransactionTemplate` bean then all begin through it; no service changes.*
- *`spring.jpa.open-in-view` is `false` (application.yaml), so no request-scoped `EntityManager` opens a connection before the binding.*
- *With **no** tenant context, `doBegin` binds `''` (unset) rather than failing. Unset is fail-closed at the policy (`RS001`), and the transactions that legitimately run without a tenant touch only PLATFORM tables: Spring Modulith's `markProcessing`/`markCompleted`/`markFailed` (`REQUIRES_NEW`), the startup republication (`findIncompletePublications`), the platform pool (D181) and `StoreScopeStartupCheck` (catalog only).*
- *Autocommit JDBC found today, to move into a transaction in the slice that owns it: `reporting.RebuildRunner` (`rebuild_run` is protected, so it would raise `RS001`; S74 reporting); `shared.IdempotencyService` reads outside a transaction on purpose (`requireNoActiveTransaction`), and the idempotency table becomes TENANT (D186), so its reads run in a short read-only transaction of their own (S71). `IdempotencyRetention` iterates tenants (D185). Boot's DataSource health check reads no table.*
- *`StoreScopedDataSource`'s re-entrant scope read during actor resolution (the `RESOLVING_ACTOR` fallback, caused by Spring Session's own transaction) disappears with D176; the fallback is removed in S72.)*

<a id="d179"></a>
### D179 ★ — RLS: a restrictive tenant policy on every tenant table, combined with the store policies

**Amends** D167 and D168 (classes, helpers, `store_of`). Everything in D168 about store visibility, `RS002`, child lookups, special tables and leakproofness stays, inside a tenant.

**Table classes** (each table belongs to exactly one; the coverage test fails otherwise):

| class | tenant predicate | store predicate | examples |
|---|---|---|---|
| **TENANT** | restrictive | none (a permissive `USING (true)` so that rows of the tenant are visible) | `customers.*`, catalog items, stock items, suppliers, loyalty, `identity.*`, `organization.company_profile`, `organization.store`, `organization.terminal` (all formerly UNSCOPED, D167) |
| **TENANT_STORE** | restrictive | D168 `store_scope` | the 66 STORE tables |
| **TENANT_STORE_OR_ALL** | restrictive | D168 | `quick_page`, `automatic_item` |
| **TENANT_CHILD** | restrictive, on the child's own `tenant_id` | D168 parent lookup | the 26 CHILD tables |
| **TENANT_SPECIAL** | restrictive | D168 special rules | `audit_event`, `stock_transfer`, `warehouse` |
| **PLATFORM** | none, registered in `shared.rls_platform_table` with a reason | none | `tenancy.*` (no grant to the application role, D177), `flyway_schema_history`, `event_publication*` (D185) |

**The canonical tenant policy** (generated by `shared.rls_protect_tenant(t regclass)`, block V1009):

```sql
ALTER TABLE t ENABLE ROW LEVEL SECURITY;  ALTER TABLE t FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_scope ON t AS RESTRICTIVE FOR ALL TO PUBLIC
  USING      (tenant_id = (SELECT shared.rls_tenant_id()))
  WITH CHECK (tenant_id = (SELECT shared.rls_tenant_id()));
-- trigger tenant_fixed BEFORE UPDATE OF tenant_id … → RS002
```

**Why restrictive.** Permissive policies combine with `OR` and restrictive ones with `AND`. The tenant predicate is then ANDed with every permissive store policy, present and future, so a too-broad permissive policy added later (a mistake) still cannot cross tenants. The D168 helpers create the store policies as permissive. `rls_protect_store_table` and its siblings now call `rls_protect_tenant` first.

**Foreign keys bypass RLS** (PostgreSQL: referential integrity checks always bypass row security). An in-module foreign key on `id` alone would therefore let a row reference **another tenant's** parent. **Every in-module foreign key becomes composite:** `(tenant_id, parent_id) REFERENCES parent (tenant_id, id)`. Cross-module references stay foreign-key-free (D6) and are checked through the owning module's API, which runs in the same tenant-bound transaction and is filtered by RLS. `CompositeForeignKeyCoverageTest` (D189) proves that every foreign key between TENANT* tables includes `tenant_id`.

**The D42 probe** (`shared.store_of(t, id)`, `SECURITY DEFINER`) now also requires `tenant_id = shared.rls_tenant_id()`:
- another **store** of the same tenant → returns its `store_id` → `require` → **403** (D42 kept);
- another **tenant**'s row → `NULL` → **404**. Across tenants, existence is never revealed.

The same applies to `inventory.stock_transfer_stores`.

**`SECURITY DEFINER` allow-list:** `shared.store_of`, `inventory.stock_transfer_stores`, `tenancy.resolve_membership`, `tenancy.active_tenant_ids` (D185). Each sets `search_path`, reads one row shape and is covered by a test.

**Indexes.** `tenant_id` leads every composite index whose first column was `store_id` or a business key (`(tenant_id, store_id, …)`), and `UNIQUE (tenant_id, id)` exists on every TENANT* table. `uuid_eq` is leakproof, so `tenant_id = …` stays an index condition under RLS (D169). The non-leakproof warnings of D169 still apply. No partitioning now. Revisit hash partitioning by `tenant_id` when the largest table passes ~200 M rows or when one tenant's vacuum disturbs others.

<a id="d180"></a>
### D180 — Schema migration and existing data (P70)

- **Adding the column without rewriting rows:** `ALTER TABLE t ADD COLUMN tenant_id uuid NOT NULL DEFAULT '<bootstrap tenant uuid>'`, then `ALTER COLUMN tenant_id DROP DEFAULT`. Since PostgreSQL 11, a non-volatile default is stored in the catalog and the table is not rewritten. **No `UPDATE` runs**, so the immutability triggers (orders V6007, fiscal V10005, settlement, cash) do not fire and D160's rule "no migration updates an immutable row" holds. The bootstrap tenant is inserted by the same migration from `organization.company_profile` (`code` from the company tax id).
- **Then, per module, in one migration:** `UNIQUE (tenant_id, id)`; business unique keys rebuilt as `(tenant_id, …)` (`CREATE UNIQUE INDEX CONCURRENTLY` is not possible inside Flyway's transaction; on development data, plain creation is fine); composite foreign keys (`NOT VALID`, then `VALIDATE CONSTRAINT`); policies through the helpers.
- **P70 = none in production:** no import tool. **P70 = yes:** a per-installation export/import tool (dump as owner, rewrite `tenant_id`, load, re-key Keycloak users through invitations) becomes its own slice and its own drill.
- **Flyway versions** (next free per block, D5): shared `V1009`, audit `V1106`, organization `V2005`, identity `V3016`, dining `V4007`, catalog `V5007`, orders `V6022`, production `V7010`, cash `V8010`, settlement `V9052`, fiscal `V10012`, customers `V11005`, promotions `V12023`, inventory `V13062`, reporting `V14006`, tenancy `V15001` (new block 15xxx). These replace the D172 reservations, which were never applied.

  *(Amended 2026-11-15, R6 G1, from the code on `main` f32e4b5: S66–S70 merged after this text was written, so the D172 versions **were** applied and eight of the numbers above collide (shared V1009, audit V1106, dining V4007, catalog V5007, production V7010, fiscal V10012, promotions V12023, reporting V14006). The R6 versions are the next free per block on f32e4b5: shared **V1010**, audit **V1107**, organization **V2005**, identity **V3016**, dining **V4008**, catalog **V5009**, orders **V6031**, production **V7011**, cash **V8012**, settlement **V9061**, fiscal **V10013**, customers **V11005**, promotions **V12024**, inventory **V13082**, reporting **V14007**, tenancy **V15001**. Wherever this document says "block V1009" for the tenant helpers, read V1010. The spec re-checks them against `main` before S71 starts.)*

  *(Amended 2026-11-15, R6 G1 — migration order and the tenant default, recommended answers accepted:*
  - *Flyway runs the blocks in ascending order, so the shared helpers (V1010) exist before every module migration and `tenancy` (V15001) runs last. That is safe because no table references `tenancy.tenant` by foreign key (PLATFORM, cross-module, D6): the bootstrap tenant id is a **fixed literal UUID** in the migrations, and V15001 inserts that tenant from `organization.company_profile` as the owner.*
  - *Per module, in one migration and in this order: (1) `ADD COLUMN tenant_id uuid NOT NULL DEFAULT '<bootstrap uuid>'` (catalog-only, no rewrite, no `UPDATE`, so the immutability triggers do not fire); (2) **`ALTER COLUMN tenant_id SET DEFAULT shared.rls_tenant_id()`** instead of `DROP DEFAULT`; (3) `UNIQUE (tenant_id, id)` on every parent before any child references it; (4) each business unique key: create the `(tenant_id, …)` one, then drop the old; (5) each in-module foreign key: add the composite one `NOT VALID`, `VALIDATE CONSTRAINT`, then drop the single-column one; (6) the policies through the helpers (restrictive `tenant_scope`, the `tenant_fixed` trigger), then the coverage registries.*
  - ***Why the database default.** With `DEFAULT shared.rls_tenant_id()`, an insert takes the bound tenant without the application writing it; an unbound insert raises `RS001`, and the restrictive `WITH CHECK` still refuses any explicit foreign value (42501). Entities map `tenant_id` as `insertable = false, updatable = false` (read-only) or not at all, so no use case gains a tenant parameter and none can forget one. `ddl-auto=validate` accepts both.*
  - *Append-only tables with immutability triggers (orders V6007, fiscal V10005, settlement, cash) need no exception: steps (1)–(6) run no `UPDATE`. The `tenant_fixed` trigger is `BEFORE UPDATE OF tenant_id`, so it does not interfere with the existing triggers.)*

<a id="d181"></a>
### D181 — Tenant lifecycle and the platform API

- **`tenancy` module, public facade `TenancyApi`** (memberships, tenant status) for `identity` and `config`. Events: `TenantProvisioned`, `TenantSuspended`, `TenantReactivated`, `TenantClosed`, `MembershipRevoked` (each carries `tenantId`).
- **Platform routes `/api/v1/platform/**`:** only tokens from `restaurantpro-ops` with the realm role `platform-operator`. They run with **no tenant context**. They touch only `tenancy.*`, as the owner, through `SECURITY DEFINER` functions or a dedicated owner connection pool of size 2 used only by this module (`TenancyOwnerDataSource`, ArchUnit-restricted to `tenancy.adapter`). Platform operators never read tenant business data (D183).
- **Onboarding** (`POST /platform/tenants`, idempotent, D24): in one recorded multi-step command (D63):
  1. create the tenant (`PROVISIONING`);
  2. create the Keycloak organization;
  3. in a tenant-bound transaction, create the company profile, the first store and the administrator account (local), with the Administrator role on all stores;
  4. create or invite the Keycloak user (execute-actions email: verify email, set password, configure OTP);
  5. create the `ACCOUNT` membership;
  6. set the tenant `ACTIVE`.

  Each external call follows D48 (persist, call, record). This replaces D30's environment bootstrap.
- **Suspension** (unpaid, abuse, legal hold): every tenant request → 403 `TENANT_SUSPENDED`; jobs skip the tenant (D185); data untouched. **Closure:** suspend, export (D188), then delete after the retention period in the contract (§11). Deletion is a platform job run as the owner, tenant by tenant, audited in `tenancy.platform_audit_event`.
- **Platform audit:** `tenancy.platform_audit_event` (append-only, D18 trigger) for every platform action: operator, tenant, action, reason.

<a id="d182"></a>
### D182 — POS terminals: a Keycloak confidential client per terminal, PIN verified by the backend

**Amends** D26 (POS part), ADR 0008 and ADR 0013 (the device token is replaced; the PIN flow is kept).

**Enrolment** (replaces the long-lived device token):
1. An administrator creates the terminal (`TERMINAL_MANAGE`, sensitive, D184). The backend asks Keycloak through `tenancy-provisioner` (D48) to create the client `pos-<terminalId>` with authenticator `client-jwt` (private_key_jwt) and no secret, and returns a **one-time enrolment code** (24 h, hashed, as D26).
2. The POS app generates a **P-256 key pair** and keeps the private key in the platform keystore where available (Android Keystore, Windows CNG/TPM, macOS Keychain). It calls `POST /api/v1/pos/enrolment {code, publicJwk}` (unauthenticated, throttled per address and per code, five attempts).
3. The backend verifies the code, registers the public key on the Keycloak client (JWKS), creates the `TERMINAL` membership (`subject` = the client's service-account user id) and consumes the code.
4. The terminal obtains access tokens with `grant_type=client_credentials` and a signed client assertion (RFC 7523). There is no shared secret anywhere.

**Revocation:** `TerminalRevoked` disables the Keycloak client and revokes the membership. The next request is refused within the cache window (P73) and the next token request fails at once.

**Employee on the terminal** (kept from D26/D39/ADR 0013, now on top of the terminal token):
- `POST /api/v1/pos/sessions {employeeCode, pin}` with the terminal's bearer token. BCrypt verify; lockout after 5 failures, manager unlock (unchanged). It returns an **opaque POS session token** (256-bit random, stored as SHA-256 in `identity.terminal_session` with `tenant_id`, bound to the terminal, idle and absolute expiry as today).
- Every POS request carries **both** `Authorization: Bearer <terminal JWT>` and `X-Pos-Session: <token>`. The session must belong to the same terminal as the token's membership, otherwise 401. The `Actor` is `EMPLOYEE` with the terminal's store (D39 unchanged; POS write endpoints still never take `storeId` or `terminalId` from the request).
- Approver code + PIN in headers (D28, D40) unchanged; never stored by idempotency.

**Scale note.** One Keycloak client per terminal is the cleanest revocation unit. *(Amended 2026-11-15, R6 G1 — recommended answers accepted:*
- ***Private-key storage.** The POS is a React web app (spec §2), not a native app, so the platform keystores above apply only if it is later packaged natively. The key pair is a WebCrypto **non-extractable** P-256 `CryptoKey` kept in IndexedDB; the browser signs the RFC 7523 assertion and the key never leaves it. Clearing the site data un-enrols the terminal, and an administrator re-enrols it (a new code). The POS calls the Keycloak token endpoint directly (the client's web origin is set).*
- ***Enrolment throttling.** Per code: 5 failed attempts burn it. Per source address: 10 attempts per minute, then 429. Both are counted and alerted (D149).*
- ***`tenancy-provisioner` scope.** Keycloak fine-grained admin permissions (v2, Keycloak 26.2+) limited to organizations, organization members and clients; S73 proves by test that the provisioner **cannot** change realm settings, flows or other clients' roles. If v2 cannot restrict client management to the `pos-*`/`bridge-*` clients, S73 stops and the choice comes back to the user (open item O1).*
- ***POS frontend changes** (out of R6's backend scope, recorded for the frontend run): an enrolment screen, key generation, token renewal before expiry, sending `X-Pos-Session` on every call, dropping `X-Terminal-Token`, and "sem ligação" when Keycloak or the backend is unreachable.)* Keycloak handles tens of thousands of clients, but admin-console listing slows down; the provisioner pages and filters by `clientId` prefix. **Alternative considered:** one shared `pos` client plus a service user per terminal (fewer clients, but revocation through user disable and a credential type Keycloak manages less well for machines).

<a id="d183"></a>
### D183 — Support access across tenants

**Supersedes** D83's mechanism and D145 (support accounts and MFA).

- Support staff are users of `restaurantpro-ops`, never of a tenant realm.
- *(Amended 2026-11-15, R6 G1: the grant lasts **at most 24 h**, not 72 h, matching the R2 support grant (D83, glossary "Support grant"): the shorter window is the safer one and nothing asked for longer.)*
- A tenant administrator grants **time-boxed support access** (`SUPPORT_ACCESS_MANAGE`, sensitive, with reason, at most 72 h). This creates a `SUPPORT` membership with `valid_until`, linked to a local support role inside that tenant (the D83 roles, which withhold sensitive permissions).
- Support calls `/backoffice/**` with an ops-realm token and `X-Tenant-Id`. `resolve_membership` accepts the ops issuer **only for `kind SUPPORT`**. Every request requires `acr ≥ 2` (D184).
- Every support action is audited in the tenant's own `audit_event` with `actor_kind = SUPPORT`, and a support session start is notified to the tenant's administrators (in-app alert).
- **No platform "god mode".** Nothing in the application gives cross-tenant business data access. A database operator (superuser) can, which is the §10 "infrastructure administrator" risk, controlled by segregation and logging of console access.

<a id="d184"></a>
### D184 — MFA and step-up through Keycloak (RFC 9470)

**Amends** D142 (how freshness is proven); the sensitive-permission set of D142 and its rules for `requireAny` are kept. **Supersedes** D140 (MFA modes and the LAN/remote rule, meaningless in SaaS).

- **MFA is mandatory for every BackOffice account** (Keycloak required action "Configure OTP", or WebAuthn). Rationale: in SaaS every BackOffice login is remote.
- **Levels:** Keycloak LoA flow, `acr = "1"` password, `acr = "2"` password + OTP/WebAuthn. `acr` and `auth_time` are set at authentication and do not change on refresh (RFC 9470 §6.1), so they are trustworthy for freshness.
- **Sensitive permission (D142):** the account's token must have `acr ≥ 2` and `now − auth_time ≤ restaurantpro.security.step-up-max-age` (default **5 min**). Otherwise **401** with `WWW-Authenticate: Bearer error="insufficient_user_authentication", acr_values="2", max_age=300` (RFC 9470 §3), plus a ProblemDetail `MFA_STEP_UP_REQUIRED`. The BFF sends the user to Keycloak with those values and repeats the request **with the same `Idempotency-Key`** (a 401 is never stored, D47).
- POS (`EMPLOYEE`) behaviour stays as D142 amended:*(Amended 2026-11-15, R6 G1 — recommended answers accepted: (1) **Keycloak:** the browser flow uses the conditional *Level of Authentication* steps (LoA 1 = password, LoA 2 = OTP or WebAuthn, max age 300 s for LoA 2), the realm maps `acr` `"1"`/`"2"` to those levels, and `backoffice-bff` sets "default ACR" `1`. (2) **The BFF never replays a request.** On a 401 with `insufficient_user_authentication` it passes the challenge to the SPA as the ProblemDetail; the SPA keeps the pending request and its `Idempotency-Key`, navigates top-level to the BFF's step-up endpoint (`acr_values=2&max_age=300`), and on return repeats the request itself. (3) The backend trusts only the token's own `acr` and `auth_time`, never the requested values. (4) The D142 sensitive set and its `requireAny` rule are unchanged.)*
- POS (`EMPLOYEE`) behaviour stays as D142 amended: a sensitive permission from a PIN session outside `requirePos*` → 403 `MFA_ACCOUNT_REQUIRED`. Approver PINs unchanged.
- Recovery codes, enrolment codes and break-glass (D143) move to Keycloak: recovery codes are a Keycloak credential type, and an administrator's reset is a Keycloak admin action (remove OTP credential + required action). The local `mfa_*` tables (S56) are dropped after migration.

<a id="d185"></a>
### D185 — Actor-less contexts: every system scope is inside one tenant

**Amends** D166 and D46 (single instance).

- **There is no cross-tenant scope for the application role.** A job that concerns every tenant iterates: `tenancy.active_tenant_ids()` (`SECURITY DEFINER`, returns ids only, ACTIVE tenants) → for each tenant, `TenantScopes.run(tenantId, SystemScope.X, …)` in **its own transaction**. One tenant's failure is logged, counted and alerted for that tenant, and does not stop the others. Each tick handles a bounded batch per tenant (fairness).
- `SystemScope` (D166) keeps its reach semantics, now inside a tenant: `ALL` = every store of **that** tenant.
- **Listeners:** every event record carries `UUID tenantId` (ArchUnit: every type published through `ApplicationEventPublisher` in `*.model.event` has a `tenantId` component). The advisor (D166) binds `tenantId` plus the event's `storeId` (or the method's `@SystemScoped`) before the listener's `REQUIRES_NEW` transaction begins. An event without `tenantId` fails before the transaction.
- **`event_publication*` stays PLATFORM** (Spring Modulith reads it at startup and for D88 recovery across tenants). The serialized event carries `tenantId`, so republication binds the right tenant. The table never reaches a client.
- **Executor tasks:** `TenantScopes.propagate(Runnable)` captures tenant + store scope (D166 unchanged otherwise).
- *(Amended 2026-11-15, R6 G1, from the code: the 17 `@Scheduled` methods split three ways. **Per tenant** (iterate `active_tenant_ids()`): `AttemptSweeper`, `InDoubtResolver`, `InDoubtGauge`, `CreditChargeSweeper`, `CashDiscrepancyAlertSource`, `StockBalanceReconciliationJob`, `ReservationNoShowJob`, `ProductionMetrics`, `PrintWorker` (dispatcher, D187), `IdempotencyRetention`. **PLATFORM, no tenant:** `EventPublicationStats`, `EventPublicationRecovery` (×2), `EventPublicationAlertSource`, `ChangeNotifier` housekeeping (in memory). **Removed:** `LoginThrottling` (BackOffice brute force moves to Keycloak, D175; PIN lockout stays per terminal session, D182) and `SupportGrantExpiry` (support validity is `tenant_membership.valid_until`, D183). The only event without a store, `CompanyBootstrapped`, is replaced by `TenantProvisioned` (D181).)*
- **Several backend instances (horizontal scaling, D188):** D46's single-instance assumption no longer holds. Jobs take a lease per `(job, tenant)` in `shared.job_lease` (compare-and-set on `lease_until`, PLATFORM class); the existing `SKIP LOCKED` and compare-and-set work stays correct under overlap. SSE fan-out across instances uses PostgreSQL `LISTEN/NOTIFY` on a per-tenant channel name (`notify_<tenant>` hashed), or Redis if it is adopted later.

*(Amended 2026-11-15, R6 G1 — recommended answers accepted: (1) **`shared.job_lease(job text, tenant_id uuid, holder text, lease_until timestamptz, PRIMARY KEY (job, tenant_id))`**, PLATFORM class, written by the application role; acquire = `INSERT … ON CONFLICT (job, tenant_id) DO UPDATE SET holder = …, lease_until = now() + ttl WHERE job_lease.lease_until < now() RETURNING` (no row = another instance holds it). (2) **Fairness:** each tick takes at most N items per tenant and starts the tenant iteration at a rotating offset, so a large tenant cannot starve the rest. (3) **SSE: one channel, not one per tenant.** `pg_notify('change_notice', '<tenantId>:<storeId>:<topic>:<id>')` inside the writing transaction (delivered only on commit, which replaces the current after-commit hook); each instance holds one `LISTEN` connection and forwards to its own emitters keyed `(tenantId, storeId)`. The payload carries ids only, as today. Many per-tenant channels would mean one `LISTEN` per tenant per instance for no isolation gain.)*

<a id="d186"></a>
### D186 — Isolation outside the database

| channel | rule | proof |
|---|---|---|
| In-memory caches (catalog snapshot, `OrganizationApi` lookups, alert aggregation, JWKS excluded) | keys include `TenantId`; only `shared.tenancy.TenantScopedCache` may build a Caffeine/Spring cache in a module | ArchUnit `cachesAreTenantScoped`; `TenantCacheIsolationTest` |
| SSE notifications (D60) | topic = `(tenantId, storeId, topic)` | `NotificationTenantIsolationTest` |
| Idempotency (D24, D38) | scope `"<tenantId>:<op>:<kind>:<id>"`; table is TENANT class | IT: same key in two tenants → two records |
| Files and object storage (exports, attachments) | S3 key prefix `tenants/<tenantId>/…`; presigned URLs issued only after the tenant-bound authorization; bucket policy denies listing | IT |
| Logs and traces | MDC `tenant_id` on every line after resolution; never in metric labels (P74) | log contract test |
| Audit (D20) | `audit_event.tenant_id NOT NULL`; D82 queries tenant-bound | `<Module>RowLevelSecurityTest` |
| External integrations (fiscal adapter, payment provider, D53/D54) | credentials per tenant in AWS Secrets Manager under `restaurantpro/<env>/tenants/<tenantId>/…`, read through an adapter that requires the current `TenantContext` | unit + IT with two tenants |
| Rate limiting and noisy neighbours | token bucket per tenant at the gateway; per-role `statement_timeout = 15s` and `idle_in_transaction_session_timeout = 60s` on `restaurantpro_app` | load smoke |

*(Amended 2026-11-15, R6 G1, from the code on f32e4b5: there is no Spring/Caffeine cache today. The only memoizing cache is `reporting.ReleaseFamilies` (keyed by `releaseId`, never evicted); its key becomes `(tenantId, releaseId)`. Per-bean alert and gauge state (`CashDiscrepancyAlertSource`, `InDoubtGauge`, `ProductionMetrics`, `ExportMonitor`) becomes per tenant with the D185 iteration. `TenantScopedCache` is **not** built until a second cache exists (YAGNI); `cachesAreTenantScoped` instead forbids `@Cacheable`, `CacheManager` and Caffeine outside an allow-list, which is empty. SSE: `ChangeNotifier` keys emitters by `storeId` (globally unique); the subscription is authorized under the tenant-bound `Actor`, and the key becomes `(tenantId, storeId)`. Idempotency: the scope built at `Actor` (`op:kind:userId`) gains the tenant prefix and the key `(scope, key)` becomes `(tenant_id, scope, key)`. `CompanyProfile` lookups (`findById(TRUE)`, 12 callers of `OrganizationApi.companyProfile()`) become tenant-bound reads of the one row per tenant.)*

<a id="d187"></a>
### D187 — Printers and cash drawers in SaaS: a print bridge on the store LAN

**Amends** D57 (where the transport runs), D154–D158 (profiles, styles and the kick are unchanged, but are executed by the bridge) and the `PrintWorker` of D166. Consequence of SaaS: the central backend cannot open TCP 9100 on a restaurant's LAN.

- **Print bridge:** a small agent per store (Java, packaged as a service or container on any always-on device on the LAN). It is a Keycloak confidential client (`bridge-<id>`, private_key_jwt, `BRIDGE` membership bound to one store), enrolled exactly like a terminal (D182).
- **Outbound only:** the bridge holds an HTTPS long-poll/SSE to `GET /api/v1/bridge/jobs` and claims jobs with a lease (`SKIP LOCKED`, D50 §7.2 retry rules kept). It sends ESC/POS over TCP 9100 to the printers of its store and reports `POST /bridge/jobs/{id}/outcome` (`ACCEPTED`, `NOT_CONNECTED`, `INTERRUPTED`, as D57). The backend's `PrintWorker` becomes a dispatcher: it queues and never connects to printers.
- **No Internet means no new POS operations.** Terminals do not confirm new operations and do not simulate success (§11, kept). Jobs already queued are printed when the bridge reconnects. The bridge keeps no business data.

<a id="d188"></a>
### D188 — Database roles, hosting and continuity

**Amends** D163 (roles kept), D32/D98 (backups), D151 (backup status).

- **Roles (D163) kept:** `restaurantpro_owner` (`BYPASSRLS`, owns everything, Flyway, the platform pool), `restaurantpro_app` (no `BYPASSRLS`, the request and job pool), and an operations superuser that the backend never holds. **(b) Fallback if the managed PostgreSQL refuses `BYPASSRLS` (B8):** the owner role gets no `BYPASSRLS`, and the tables keep `FORCE ROW LEVEL SECURITY`. DDL is not subject to RLS, so schema migrations are unaffected. Data migrations bind a tenant explicitly (`set_config` in the migration) and loop over tenants. The platform pool (D181) touches only PLATFORM tables, which have no policy. *(Amended 2026-11-15, R6 G1: under fallback (b) the owner is subject to RLS, so the tenant export and the tenant-restore procedure bind the tenant explicitly (`set_config('app.tenant_id', …, true)` in each transaction, `pg_dump --enable-row-security` or per-table `COPY` inside a bound transaction), and the S75 drill runs both ways. The B8 spike decides which one ships.)*
- **Hosting (P75):** PostgreSQL 18 managed (RDS or Aurora, Multi-AZ), backend ≥ 2 instances (ECS/Fargate or EKS) behind an ALB, Keycloak ≥ 2 nodes with its own database, a connection pooler (RDS Proxy or PgBouncer in transaction mode; compatible because of D178).
- **Continuity:** PITR on the whole cluster (RPO ≤ 15 min, the §11 target, now met by the managed service). A **tenant-level restore** is a procedure, not a button: restore the cluster to a point in time into a staging instance, export that tenant's rows as the owner (`WHERE tenant_id = …`, in module order), and replace the tenant's rows in production in a maintenance transaction with the tenant suspended. Drilled in S75.
- **Per-tenant export** (contract end, portability, P70 import): `deploy/tenant-export.sh <tenantId>` produces an encrypted archive (the D98 encryption rules apply) of every TENANT* table filtered by tenant.
- **Encryption at rest** by the managed service (KMS), and the D98 off-site, recipient-only keys rule for exported archives.

<a id="d189"></a>
### D189 — Tests and proofs

**Harness:**
- `TestcontainersConfiguration` keeps the provisioned roles (S64). A shared **Keycloak Testcontainer** (pinned image, realm imported from `deploy/keycloak/realm-restaurantpro.json`) is used only by the identity and end-to-end suites.
- Every other test uses Spring Security's `jwt()` request post-processor or a `TestJwtFactory` signed by a test key that the test `JwtDecoder` trusts. `TenantTestSupport` creates tenants A and B with **identical store, item and employee codes**.
- The D171 fixture fallback becomes "bootstrap tenant, ALL stores" on a marked test thread only; `@StrictTenantScope` turns it off.

**Suites:**

| test | proves |
|---|---|
| `JwtValidationTest` | `alg=none`, `HS256` signed with the public key, wrong `iss`, wrong `aud`, expired, `nbf` in the future, an ID token, a refresh token, an unknown `azp` for the route → each 401 |
| `TenantResolutionTest` | no membership → 403; a forged `X-Tenant-Id` of an existing foreign tenant → 403 with the same body as a random UUID; several memberships without the header → 400; suspended → 403; an `organization` claim of another tenant changes nothing; a revoked membership is refused within 30 s and at once after the eviction event |
| `TenantBindingTransactionManagerTest` | the variables are set inside the transaction and `''` after commit and after rollback on the same backend pid; `REQUIRES_NEW` rebinds; an autocommit statement on a protected table → `RS001`; a failing `set_config` rolls back and throws; `TenantScopes.run` inside a transaction throws |
| `TenantIsolationTest` (per module, with tenants A and B) | B's rows invisible to A by list, by id (404 through the API, not 403) and by bulk report; insert with B's `tenant_id` → 42501; update of `tenant_id` → `RS002`; a child referencing B's parent → FK violation (composite FK) |
| `<Module>RowLevelSecurityTest` (D171) | unchanged store assertions, now inside tenant A |
| `RowLevelSecurityCoverageTest` (extended) | every table has `tenant_id NOT NULL` + the restrictive `tenant_scope` + `UNIQUE (tenant_id, id)` + `RS002`, or is PLATFORM with a reason; the `SECURITY DEFINER` allow-list; the application role has no privilege on `tenancy.*` |
| `CompositeForeignKeyCoverageTest` | every foreign key between TENANT* tables includes `tenant_id`; every business unique key includes `tenant_id` |
| `PosTerminalAuthTest` | enrolment with a used or expired code → refused; a token of terminal T1 with a POS session of T2 → 401; a revoked terminal → refused; lockout after 5 PINs |
| `StepUpTest` | `acr=1` on a sensitive permission → 401 with the RFC 9470 header; stale `auth_time` → 401 `max_age`; retry with the same `Idempotency-Key` runs once |
| `ActorlessTenantScopeTest` | every job iterates tenants, binds each, skips suspended ones, survives one tenant failing; every listener binds the event's tenant |
| ArchUnit | `noTenantIdFromRequests`, `eventsCarryTenantId`, `cachesAreTenantScoped`, `onlyTenancyAdapterUsesOwnerDataSource`, plus D171's rules |
| `PgBouncerTransactionModeTest` (deploy drill) | through PgBouncer in transaction mode, alternating tenants on 1 server connection never see each other's rows |

**Performance:** the R3 and RLS plan tests run as the application role in tenant A with B's rows present, at 100 tenants of seeded data. The load smoke checks §12 p95 < 500 ms with 10 terminals per tenant across 20 tenants.

*(Amended 2026-11-15, R6 G1 — recommended answers accepted, from the code on f32e4b5: (1) Most module tests act through `@WithActor` (test sources), not through a login session, so they keep working once `@WithActor` binds the bootstrap tenant; the ~145 test files that touch sessions, CSRF or the login flows change in S72 to the `jwt()` post-processor or `TestJwtFactory`. (2) **Fixture fallback:** "bootstrap tenant, ALL stores" on the marked test thread only, exactly the D171 mechanics; `@StrictTenantScope` turns it off for the isolation suites. (3) **Keycloak Testcontainer:** one shared container per JVM, started lazily only by the identity and e2e suites. (4) **Run-time budget:** the full gate may grow at most 20 % over the RLS close (3354 tests); S76 records the time. (5) The RLS run's open A77 load gate (`POST /pos/orders/{id}/discounts` p95 701–962 ms) is carried into A92 and must be baselined without RLS before S76.)*

<a id="d190"></a>
### D190 — Rollout and slices

**Supersedes** D172 (S66–S70). Their per-module store-policy work moves into S74, with the tenant policy added in the same migration. *(Amended 2026-11-15, R6 G1: S66–S70 were built and merged (PRs #96–#101), so the store policies already exist; S74 adds only the tenant column, composite keys and the restrictive tenant policy, through the now-tenant-aware helpers.)*

| slice | content | blocked by |
|---|---|---|
| **S71 Prefactor** (alone) | `tenancy` module and V15001; `shared.tenancy.TenantContext`/`TenantScopes`; `TenantBindingTransactionManager` (removes `StoreScopedDataSource`); V1009 helpers (`rls_tenant_id`, `rls_protect_tenant`, PLATFORM registry, tenant-aware `store_of`); test harness (`TenantTestSupport`, fixture fallback) | — |
| **S72 Identity on Keycloak** | resource server, `TenantResolutionFilter`, BFF (P69), step-up (D184), drop Spring Session and the local MFA tables, `identity` migration V3016 | S71 |
| **S73 Devices** | terminal enrolment and POS sessions (D182), print bridge and the dispatcher (D187) | S72 |
| **S74 Tenant columns and policies** | per module (dining, catalog, orders, production, cash, settlement, fiscal, customers, promotions, inventory, reporting, audit, organization): `tenant_id`, composite keys, the combined policies, module isolation tests. Split into S74a–S74d by module group to run in parallel. | S71 |
| **S75 Platform and deploy** | onboarding/suspension API (D181), Keycloak realm export and dev Compose, B8 spike, tenant export and the tenant-restore drill, PgBouncer drill | S71 |
| **S76 Integrate** (alone) | full suite as the application role, load smoke at 20 tenants, coverage tests across all schemas, ADR 0040 → Accepted | all |

---

## 3. Failure modes

| failure | behaviour |
|---|---|
| A request with a valid token and a foreign `X-Tenant-Id` | 403 `TENANT_FORBIDDEN`, logged with `sub` and the requested id, counted (`tenant_selection_refused_total`); alert on bursts |
| A code path reads a protected table outside a transaction | `RS001` → 500 `TENANT_SCOPE_MISSING`, alert (D170 pattern); nothing written |
| A write tries another tenant's `tenant_id` | 42501 → 500 `TENANT_SCOPE_VIOLATION`, CRITICAL alert |
| Keycloak unreachable | new logins and token renewals fail; requests with still-valid tokens keep working while JWKS is cached; after expiry → 401; the POS shows "sem ligação" and confirms nothing (§11) |
| JWKS rotation | unknown `kid` → one refresh → accepted; still unknown → 401 |
| A tenant is suspended mid-shift | requests refused within the cache window, at once after the eviction event; open POS sessions end; queued print jobs for it are discarded |
| A membership cache serves a revoked subject | at most 30 s (P73); sensitive permissions still require a fresh `auth_time` (D184) |
| A job fails for one tenant | the others continue; per-tenant alert; the lease expires and the next tick retries |
| An emergency `PERMISSIVE` switch (D170) | **amended:** it relaxes only the **store** scope; the tenant policy is never switchable |

---

## 4. Acceptance criteria (observable)

| # | criterion | spec |
|---|---|---|
| A80 | Two tenants with identical store, item, employee and fiscal-series codes coexist; each sees only its own data through every API route and every report. | §2 (amended) |
| A81 | A request never selects a tenant on its own: a forged `X-Tenant-Id`, a body field or a path value naming another tenant → 403 or ignored; the same 403 body for an existing foreign tenant and a random UUID. | §2, §10 |
| A82 | A resource of another tenant addressed by id → **404**; a resource of another store in the same tenant → **403** (D42). *Amended 2026-11-17 (S76, F25 decided): a store id given as an input or filter (`?storeId=`, `/stores/{id}`) outside the caller's store scope answers **403 whatever its tenant**, exactly as a random id (D42 checks the store input before any read, so there is no existence oracle); an all-stores caller gets 404 for another tenant's or a random store.* | §10 |
| A83 | The tenant is bound with `SET LOCAL` semantics: after commit or rollback the connection carries no tenant; through PgBouncer in transaction mode, alternating tenants never mix. | §10 |
| A84 | Database-level isolation without the application: with `AccessPolicy` bypassed through a test seam, reads, inserts, updates and child references across tenants are refused by RLS or by composite foreign keys. | §8, §10 |
| A85 | JWT validation refuses every case of `JwtValidationTest`; the backend starts with Keycloak down. | §10 |
| A86 | A terminal enrolls with a one-time code, authenticates with private_key_jwt, and is refused at once after revocation; the PIN lockout and approver PIN behave as in R2. | §3, §10 |
| A87 | A sensitive permission without `acr ≥ 2` and a fresh `auth_time` → 401 with the RFC 9470 challenge; after step-up the same request with the same `Idempotency-Key` runs once. | §10 |
| A88 | The full suite runs as the application role, green with 0 skipped; `RowLevelSecurityCoverageTest` and `CompositeForeignKeyCoverageTest` pass across all schemas. | §12 |
| A89 | Printing works through the bridge with no inbound port open on the store LAN; a bridge of store S cannot claim jobs of another store or tenant. | §4, §11 |
| A90 | Jobs run per tenant, skip suspended tenants, and one failing tenant does not stop the others; two backend instances never run the same `(job, tenant)` lease at once. | §7, §12 |
| A91 | Tenant export and the tenant-restore drill reproduce one tenant's data without touching another's. | §11 (amended) |
| A92 | p95 < 500 ms for simple commands and queries with 20 tenants × 10 terminals (§12). | §12 |

---

## 5. Superseded and amended decisions (index)

| decision | effect | by |
|---|---|---|
| D25, ADR 0007 | superseded (no server-side sessions) | D176 |
| D26 | BackOffice part superseded; POS part amended | D175, D182 |
| D30 | superseded (no bootstrap singleton) | D174, D181 |
| D32, D98, D151 | amended (managed PITR, tenant export) | D188 |
| D38, D24 | amended (idempotency scope with tenant) | D186 |
| D42 | amended (cross-tenant → 404) | D179 |
| D46 | superseded (several instances, leases) | D185 |
| D57, D154–D158 | amended (executed by the print bridge) | D187 |
| D83, D145 | superseded | D183 |
| D138, D144, ADR 0034 | superseded | D175 |
| D139–D141, D143, ADR 0035 | superseded (MFA in Keycloak) | D175, D184 |
| D140 | superseded (no LAN/remote rule) | D184 |
| D142 | amended (freshness from `acr`/`auth_time`) | D184 |
| P6 | superseded (SaaS only) | D174 |
| D163 | kept; fallback added | D188 |
| D164 | amended (`app.tenant_id`, transaction-local) | D178 |
| D165, P62 | superseded (`SET LOCAL` at begin) | D178 |
| D166 | amended (per-tenant system scopes) | D185 |
| D167, D168 | amended (TENANT* classes, restrictive tenant policy, composite FKs) | D179 |
| D170 | amended (the switch never relaxes the tenant) | §3 |
| D172 | superseded (slices) | D190 |
| ADR 0008, 0013 | amended (terminal is a Keycloak client) | D182 |
| ADR 0039 | amended (tenant predicate, `SET LOCAL`) | ADR 0040 |

## 5a. R6 G1 record (2026-11-15)

**Open items** (recorded, not blocking G1):

| id | item | owner |
|---|---|---|
| O1 | Whether Keycloak fine-grained admin permissions v2 can restrict `tenancy-provisioner` to the `pos-*`/`bridge-*` clients (D182). If not, the user chooses between a broader provisioner role and a dedicated admin realm. | S73, stop and ask  **Resolved 2026-11-16 (R6 build, S75 evidence on Keycloak 26.8.0): FGAP v2 selects clients only by type or id, so `manage` on clients would let the provisioner rewrite other clients (admin console redirect URIs, `backoffice-bff`). Chosen: a dedicated realm `restaurantpro-devices` holding only `pos-*`/`bridge-*` clients and a `device-provisioner` with `manage-clients` in that realm only; `tenancy-provisioner` keeps organizations, members and invites in `restaurantpro` with no Clients permission. Amends D175 (realms, clients) and D182/D187 (terminal and bridge tokens carry the devices issuer). Alternatives rejected: broad rights + deny-list (fail-open for new clients); dynamic client registration (rotating registration tokens stored as secrets).** |
| O2 | B8 spike result (D188 (a) or (b)). | S75  **Open 2026-11-17 (S76): not run — it needs an AWS account (the user runs `deploy/drills/b8-rds/README.md`). The drills support both: `test-tenant-export.sh` passes in (a) and in fallback (b). Follow-up F17.** |
| O3 | B6 production Keycloak hosting, after P75. | S75 / operations  **Open 2026-11-17: no decision; R6 ships the dev Compose Keycloak only. Follow-up F18.** |
| O4 | In-country data residency for the first contract (P75). | product  **Open 2026-11-17: product decision. Follow-up F19.** |
| O5 | A77 load gate carried from the RLS run into A92 (D189). | S76  **Measured 2026-11-17 (S76, `R6TenantLoadSmoke` run 3 on an idle machine, `deploy/load/r6-load-plan.md`): A77 met — at 1 company × 10 terminals every p95 ≤ 176 ms with RLS, and RLS adds nothing measurable over the no-RLS baseline; A92 missed — at 20 × 10, 9 of 11 gated operations p95 557–1 468 ms (BUG-0004: application time inside open transactions on a CPU-saturated single host; not RLS, membership resolution or pool size). Addressed by S77.** |

**`backend-conventions.md` rules that change** (the text changes land in the spec and are made by the named slice):

| rule today | change | slice |
|---|---|---|
| Web and security: routes "authenticated session"; CSRF SPA pattern; sessions in `shared.spring_session`, cookie | bearer JWT (D176), `iss`/`aud`/`azp` per route family, no CSRF on the API, no sessions | S72 |
| Step-up for sensitive permissions (D142, ADR 0035); No secret in an idempotency record (D143) | `acr`/`auth_time` from the JWT, RFC 9470 challenge (D184); the MFA-secret exception goes away | S72 |
| Remote address (D140) | removed (no LAN/remote rule) | S72 |
| Actor (D38); POS context (D39) | `TenantContext` resolved first (D177); `X-Pos-Session` bound to the terminal token (D182) | S71 / S72 / S73 |
| Idempotency (D24/D47) | scope prefixed by tenant, key `(tenant_id, scope, key)` (D186) | S71 |
| Store-scope check order (D42) | cross-tenant id → 404, same tenant other store → 403 (D179) | S71 |
| Company-wide data (R3, R4) | "company-wide" means tenant-wide | S74 |
| Scheduling (D46) | per-tenant iteration, `job_lease` (D185) | S71 (mechanism) / S74 (jobs) |
| Change notices (D60) | `pg_notify` channel, `(tenantId, storeId)` keys (D185) | S71 |
| Events | every event record carries `tenantId`; the advisor binds it (D185) | S71 (rule) / S74 (records) |
| Persistence | every new table: `tenant_id` with `DEFAULT shared.rls_tenant_id()`, `UNIQUE (tenant_id, id)`, composite in-module FKs, classified TENANT* or PLATFORM (D179, D180) | S71 |
| Persistence helpers / JDBC | protected-table JDBC only inside a transaction (D178) | S71 |
| Caches (new) | no Spring/Caffeine cache outside the allow-list; in-memory keys include the tenant (D186) | S71 |
| Tests: store scope in tests (D171) | bootstrap-tenant fallback, `@StrictTenantScope`, tenants A/B with identical codes, `jwt()`/`TestJwtFactory`, Keycloak container only for identity/e2e (D189) | S71 / S72 |
| Operations: metric labels | never `tenant_id` (P74) | S71 |

**R6 build amendments (2026-11-15 to 2026-11-17, recorded in the run ledger; S76):**
- *D179/D180 Indexes:* the per-table recipe gains an explicit step: every `store_id`-led (or business-key-led) index is rebuilt leading with `tenant_id` (two slices missed it at review).
- *Platform writes:* a platform `SECURITY DEFINER` function called in a tenant-bound transaction raises `TN004` → 403 `PLATFORM_FORBIDDEN` (S75); `/platform/**` runs unbound (`SystemScope.PLATFORM`, S72).
- *BFF (P69):* error codes `BFF_SESSION_EXPIRED` (401) and `BACKEND_UNAVAILABLE` (502); logout ends the Keycloak session back-channel with no `id_token_hint` in URLs; one instance until a shared session store is decided (follow-up F04).
- *Standard roles per tenant:* two extra PLATFORM tables, `identity.role_template(_permission)`, hold no tenant data; a migration that changes a seeded role's permissions updates its template too (S72).
- *Re-plans:* S71b, S72b, S74x and S76b were added; S73 is also blocked by S74a and S75, S75 by S72; Flyway: S71b `shared/V1012`, S76 `V1013` (not needed), S76b `V1014`. See the PRD's "Re-plans during the build".
- *A92 baseline:* a run without RLS is correct only while one company has data (the tenant predicate of tenant-wide tables is RLS's alone), so the no-RLS baseline is 1 tenant × 10 terminals, compared with RLS on the same data, and the A92 gate is a third phase at 20 × 10 (`deploy/load/r6-load-plan.md`).

**Spec-time amendments (R6 G2, 2026-11-15, approved with the spec; frozen in [r6-multitenancy.md](../prd/r6-multitenancy.md)):**
- *D178:* `shared.rls_tenant_id()` raises **`RS003`** (tenant not set), not `RS001`, so tenant and store failures are told apart (`TENANT_SCOPE_MISSING` vs `STORE_SCOPE_MISSING`).
- *D181:* onboarding is an event chain (`TenantProvisioned` → `organization` → `CompanyProvisioned` → `identity` → `TenancyApi.activate`), because a `tenancy` orchestrator calling `identity` would form a cycle with `identity → tenancy`. D13 gains `organization → tenancy`. Platform writes go through `SECURITY DEFINER` functions; **no owner pool** in the backend, so owner credentials never enter the process.
- *D185:* the `StoreScopeAdvisor` invokes a reach-`ALL` `@SystemScoped` method once per active tenant (lease, own transaction, failure isolation); module jobs do not change code.
- *D190:* S73 is also blocked by S74a (`production/`) and S75 (the real `IdentityProviderAdmin`); the organization module's tenant migration moves into S71; every event record gains `tenantId` in S71.

## 6. Process and what I need from you

The same gates as R1–R5 and RLS: G1 is this document; a PRD for S71–S76 follows approval (G2/G3); each slice is TDD in its own worktree (G4), reviewed by a fresh code reviewer (G5); S76 integrates; architecture review (G6); you merge (G7).

1. Approve D174–D190, or override by id. Check **D177**, **D178**, **D179** and **D182** in particular.
2. Answer P69–P75, or accept the recommendations. **P69, P70 and P75 shape the most work.**
3. Resolve B7/B8 (P70, and the managed PostgreSQL `BYPASSRLS` check).

---

*Xavier Francisco Nhagumbe, Engenheiro de Software*
