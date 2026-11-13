# 0039. PostgreSQL row-level security on store_id as defence in depth, with separate database roles and a scope set at connection checkout

Status: Proposed 2026-11-13 (D162 ★, D163 ★, D165 ★, with D164 and D166–D173; P61–P68). Source: [rls-decisions.md](../design/rls-decisions.md); spec §2, §8, §10, §11, §14. Amends, once accepted, D4/D11 (tests connect as the application role) and D32/D98 (restore provisions roles). S70 sets it to Accepted after approval.

## Context

§2: one installation per company, "store_id distingue operações e permissões", no Tenant, no `tenant_id`, no tenant claims; "Manter controlo de acesso por loja dentro de cada instalação". §8: "autorização validada no servidor e integridade das referências entre lojas". §10: "Privilégio mínimo por função/loja". §14: "Operador sem permissão não altera fiscal, preços ou fechos através da API".

Store isolation is enforced only by `AccessPolicy` (D27, D42): one forgotten filter or a resource read by an id from another store is enough to cross stores. The backend connects as the PostgreSQL superuser, so nothing in the database limits it. The user asked for SaaS multi-tenancy (a shared schema with `tenant_id`, Keycloak), which contradicts §2 and ADR 0034, and then chose (2026-11-13) row-level security on `store_id` inside each installation, behind `AccessPolicy`, with no spec change.

## Decision

- **RLS is the second line.** `AccessPolicy` stays the only authority for decisions and HTTP errors. The database scope is coarse: the stores where the actor holds any permission (installation-wide = all), the terminal's store for a POS session, the event's store for a listener, a named system scope for jobs.
- **Three roles:** the superuser for operations only; `restaurantpro_owner` (no superuser, `BYPASSRLS`) owns every object and runs Flyway; `restaurantpro_app` (no superuser, no `BYPASSRLS`, owns nothing) is the pool. Policies are `TO PUBLIC`, so they bind only the application role. An idempotent provisioning file, run by a one-shot Compose service at every start, by restore, by the tests and in development, creates the roles, transfers ownership and grants.
- **The scope is written at every connection checkout** by a wrapping `DataSource` (`app.all_stores`, `app.store_ids`, `app.scope_origin`, all overwritten every time), which covers JPA, JDBC, Spring Session and the event publication registry, statements outside transactions, `REQUIRES_NEW` and read-only transactions, and cannot leak between borrowers.
- **Fail closed:** a protected table touched with no scope raises `RS001`; a refused write raises `42501`; a store change raises `RS002`; each becomes a coded 500, a counter and an alert.
- **Coverage:** 96 tables protected (97 after R5) with ENABLE + FORCE + one generated canonical policy + a store-change trigger; child tables by parent lookup; `stock_balance` gets a denormalised `store_id`; company-wide and identity tables are registered unscoped with a reason. A `SECURITY DEFINER` probe `shared.store_of` keeps D42's 403 for another store's resource.

## Alternatives considered

- **`tenant_id` multi-tenancy with Keycloak:** contradicts §1, §2 and ADR 0034.
- **Two roles (superuser owner + application role):** smaller, but the backend keeps the superuser password.
- **`SET LOCAL` at transaction begin (`TransactionExecutionListener`):** the literal wording; statements outside a transaction get no scope and fail.
- **No RLS, more ArchUnit rules on queries:** cannot see SQL strings or ids from requests; no protection against injected SQL.
- **One schema or one database role per store:** changes every query or explodes roles; schemas organize modules (§2).

## Consequences

- Every scheduled job, runner, listener, executor task and DB-backed gauge needs an explicit scope (ArchUnit rules, a registry test). Listeners of events without `storeId` must declare a system scope.
- Two new secrets per installation; a one-shot provisioning service; `restore.sh` restores with `--no-owner --no-privileges` and provisions. The R5 image still runs on the migrated database (it connects as the superuser), so rollback needs no undo.
- The test harness connects as the application role; privileged fixtures use an owner connection.
- Query plans keep their indexes, but a predicate on a non-leakproof operator (`numeric`, `jsonb`, `LIKE`) stops being an index condition on a protected table; plan tests guard the hot queries. Parent-lookup policies cost a primary-key probe per child row.
- An emergency `PERMISSIVE` mode turns filtering off without DDL, alerted and audited.
- Proofs: `StoreScopePoolReuseTest`, `StoreScopedDataSourceTest`, `ActorlessStoreScopeTest`, `RowLevelSecurityCoverageTest`, `StoreScopeStartupCheckTest`, `<Module>RowLevelSecurityTest`, the R3 plan tests as the application role, the deploy drills.
