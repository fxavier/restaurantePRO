# 0040. SaaS multi-tenancy: shared schema with `tenant_id`, PostgreSQL RLS bound with `SET LOCAL`, Keycloak as the identity provider

Status: Proposed 2026-11-15 (D174 ★, D177 ★, D178 ★, D179 ★, with D175, D176 and D180–D190; P69–P75). Source: [multitenancy-decisions.md](../design/multitenancy-decisions.md); spec §1, §2, §10, §11 as amended on 2026-11-15. **Supersedes** [ADR 0034](0034-keycloak-not-core-idp-oidc-federation.md), [ADR 0035](0035-native-totp-mfa-and-step-up.md) and [ADR 0007](0007-server-side-sessions.md). **Amends** [ADR 0008](0008-two-login-flows.md), [ADR 0013](0013-pos-context-from-session.md) and [ADR 0039](0039-row-level-security-on-store-id.md). S76 sets it to Accepted after approval.

## Context

Up to 2026-11-13 the product was installed per company (spec §2: "Não existem Tenant… tenant_id em tabelas, tenant claims"), with local identity (ADR 0034) and store-level RLS inside each installation (ADR 0039, S64 and S65 merged). On 2026-11-15 the user decided to run restaurantPRO as a SaaS. All companies share one central installation, with a shared schema, a `tenant_id` and RLS, and Keycloak issues the JWTs. Isolation must hold in both the application and the database. A `tenant_id` received in a request is never trusted. The tenant is set in the transactional context and applied with `SET LOCAL` before any access to protected data. The local, offline-first server per company is dropped, the store-level RLS is kept and combined with the tenant RLS, and each POS terminal becomes a Keycloak confidential client.

## Decision

- **Tenant = company.** Every tenant-owned table has `tenant_id uuid NOT NULL`, children included. Every business unique key and every in-module foreign key includes `tenant_id`, because foreign-key and unique checks bypass RLS. `UNIQUE (tenant_id, id)` exists on every tenant table.
- **Keycloak 26.x is the identity provider.** There are two realms: `restaurantpro`, with Organizations as the tenant directory, and `restaurantpro-ops` for operators and support. Keycloak owns credentials, MFA and step-up levels. Local RBAC per store stays the only authority; no claim is mapped to a permission.
- **The backend is an OAuth2 resource server.** It validates signature (RS256/ES256 only), `iss`, `aud`, `exp`/`nbf`, `azp` per route family and `typ`. There are no server-side sessions. The BackOffice uses a thin BFF.
- **Tenant resolution** goes from `(iss, sub)` to an active row of `tenancy.tenant_membership`, read only through a `SECURITY DEFINER` function. An `X-Tenant-Id` header is a selection validated against those rows. It is never an assertion, and body, path and query never carry a tenant. Foreign or non-existent tenants get the same 403.
- **Binding:** a `JpaTransactionManager` subclass runs `set_config('app.tenant_id', ?, true)` (with the store-scope variables) on the transaction's connection right after begin. That is `SET LOCAL` with bind parameters. Outside a transaction a protected table raises `RS001`.
- **RLS:** a **restrictive** `tenant_scope` policy (`tenant_id = shared.rls_tenant_id()`) with `FORCE` on every tenant table, ANDed with the permissive store policies of ADR 0039. A cross-tenant id answers 404; another store of the same tenant answers 403.
- **Devices:** a POS terminal or a print bridge is a Keycloak client with `private_key_jwt`, enrolled by a one-time code. The employee PIN, lockout and approver PIN stay in the backend. Printers are reached through the outbound-only print bridge.
- **Jobs** iterate over active tenants, each in its own bound transaction. No scope spans tenants for the application role.
- *(Amended 2026-11-15, R6 G1: no membership cache — the membership is resolved on every request, so revocation and suspension hold at once on every instance; `tenant_id` columns default to `shared.rls_tenant_id()`, so inserts take the bound tenant and the application never writes it. See multitenancy-decisions.md §5a.)*

## Alternatives considered

- **Schema or database per tenant:** stronger isolation, but Flyway, catalog growth, connection handling and cost grow with the number of tenants. Rejected for cost and operational complexity.
- **Repository filters without RLS:** one forgotten filter crosses companies.
- **A tenant claim set by Keycloak, trusted as is:** the IdP becomes a second authority, and a stale or misconfigured claim crosses tenants.
- **Binding at connection checkout (ADR 0039, D165):** a session-level value can leak through a pooler in transaction mode and survives the transaction. Replaced by transaction-local binding.
- **`TransactionExecutionListener.afterBegin`:** documented by Spring for observation and statistics, not for resource management or security controls.
- **Realm per tenant:** hard isolation in Keycloak, but realm count grows with tenants.

## Consequences

- Spec §1, §2, §10 and §11 are amended. Offline operation per company is gone: without Internet, terminals confirm nothing (fail closed).
- Removed: Spring Session JDBC, CSRF on the API, the local password and TOTP tables, the bootstrap singleton company, `StoreScopedDataSource`. New: `tenancy` module (block 15xxx), Keycloak (HA, own database), the BFF, the print bridge, per-tenant secrets in AWS Secrets Manager, job leases for several backend instances.
- Every table gets a migration (fast default, no row rewrite, no immutable-row update). Every event carries `tenantId`. Every cache key, SSE topic, idempotency scope and storage prefix includes the tenant.
- A tenant-level restore is a procedure (PITR into staging, then a tenant-filtered copy), not a button.
- Proofs: `TenantResolutionTest`, `TenantBindingTransactionManagerTest`, `TenantIsolationTest` per module, `RowLevelSecurityCoverageTest`, `CompositeForeignKeyCoverageTest`, `JwtValidationTest`, `PosTerminalAuthTest`, `StepUpTest`, `ActorlessTenantScopeTest`, the PgBouncer and tenant-restore drills.
