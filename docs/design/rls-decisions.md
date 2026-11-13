# restaurantPRO backend — row-level security on store_id: decisions for approval

Status: **PROPOSED** 2026-11-13, for approval by Xavier Francisco. This document continues [backend-decisions.md](backend-decisions.md) (D1–D33), [r2-decisions.md](r2-decisions.md) (D34–D63), [r3-decisions.md](r3-decisions.md) (D64–D98), [r4-decisions.md](r4-decisions.md) (D99–D136) and [r5-decisions.md](r5-decisions.md) (D137–D161, P52–P60, A57–A68). Those decisions stay in force unless a decision below amends one by id. Ids continue from **D162, P61 and A69**. Answer by id, for example "P62: SET LOCAL" or "D167: no policy on reporting". Once approved, D162 ★, D163 ★ and D165 ★ are recorded in [ADR 0039](../adr/0039-row-level-security-on-store-id.md) (written now with status *Proposed*; the integration slice S70 flips it to *Accepted*).

Source of truth: [product_description.md](../product_description.md), cited as §n. Slices, contracts and the graph are in [PRD RLS](../prd/rls-store-isolation.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Coding rules: [backend-conventions.md](../architecture/backend-conventions.md). Baseline: `origin/feat/backend-stage5` at 41f8bc4 (main + R5 design + S55 + S62).

**User decision (binding, 2026-11-13).** The user first asked for SaaS multi-tenancy (a shared schema with `tenant_id`, Keycloak JWT). That conflicts with §1 and §2 ("Não existem Tenant… tenant_id em tabelas, tenant claims") and with ADR 0034. Given the options, the user chose **PostgreSQL Row-Level Security (RLS) on `store_id` inside each installation**, as a second line of defence behind the application authorization (`AccessPolicy`, D27, D42). **No `tenant_id`, no Keycloak, no spec change.** The flow the user asked for still applies, mapped onto stores: the application validates the session and the permissions; at the start of the database work it sets the store scope ("âmbito de loja") allowed for this actor; PostgreSQL enforces RLS policies on the `store_id` columns.

---

## 0. Blockers and questions that need you

**Blockers: none.** Docker and the shared Testcontainers PostgreSQL are in place. B4 (certified fiscal adapter) and B5 (real printers) are unrelated and stay launch conditions.

**No spec change is proposed.** RLS implements §2 "Manter controlo de acesso por loja dentro de cada instalação", §8 "autorização validada no servidor e integridade das referências entre lojas" and §10 "Privilégio mínimo por função/loja" more strongly. It adds no Tenant, no tenant claim and no company selection.

**Earlier decisions this design amends** (each named again in its decision):
- **D4 / D11** (one shared Testcontainers PostgreSQL, one role): tests connect as the application role; Flyway and privileged fixtures use the owner role (D171).
- **D25 / D30 / D32 / D98** (deploy: one database role, `restaurantpro`, used by the backend and every script): three roles (D163); restore runs the role provisioning after `pg_restore`.
- **D42** (store-scope check order): unchanged in behaviour. The "read only its storeId" step goes through one probe function that sees every store, so another store's resource still answers 403, not 404 (D168).

**Questions only you can answer.** Each has my recommendation; the run is built with it unless you override it.

| id | question | recommendation (default) | what changes if you choose otherwise |
|---|---|---|---|
| P61 | Database roles. | **Three roles:** the existing superuser `restaurantpro` for operations only (backup, restore, PITR, console), a new **`restaurantpro_owner`** (no superuser, `BYPASSRLS`, owns every object, used by Flyway), a new **`restaurantpro_app`** (no superuser, no `BYPASSRLS`, owns nothing, used by the pool). The backend container no longer holds the superuser password (D163). | **Two roles:** the superuser stays the owner and Flyway's login; only `restaurantpro_app` is added. No ownership transfer, one secret fewer, but the backend keeps the superuser password (as today), so a compromised backend can still run `COPY … PROGRAM` or `ALTER SYSTEM SET archive_command` on the database server. |
| P62 | Where the scope is set. | **At every connection checkout** from the pool, through a wrapping `DataSource`: `set_config(…, false)` writes every variable unconditionally before the connection is handed out. A transaction checks out once, so this is "at the start of every transaction", and statements outside a transaction are covered too (D165). | **`SET LOCAL` at transaction begin**, through a `TransactionExecutionListener`: exactly the user's wording and no session state, but a statement outside a transaction runs with no scope and fails (P63). The same tests apply. |
| P63 | What a policy does when no scope is set. | **Raise an error** (`RS001`), which the API maps to a coded 500 `STORE_SCOPE_MISSING`: a code path that forgot its scope is a bug, and a silent empty result would hide it (D164, D170). | **No rows, no error:** quieter, but a listener or job without a scope then silently does nothing (for example, consumes no stock) and only reconciliation would notice. |
| P64 | Child tables without `store_id` (order lines, document lines…). | **Policy by parent lookup** on 26 child tables (no data migration, no entity change, no update of immutable rows), and a **denormalised `store_id`** on `inventory.stock_balance` only, because it is a mutable projection that is read in bulk (stock valuation) and locked on every sale (D167, D168). | **Denormalise every child:** fastest reads (the prototype: about 1.5× the no-RLS time for a 15 000-row read, against 5–7× for the parent lookup), but backfills of append-only fact tables (their immutability triggers must be disabled during the migration) and a `storeId` field on 26 entities. **No policy on children:** the leanest, but a child read by a foreign id from the request (an IDOR) is then not covered. |
| P65 | Scope of an event listener. | **The event's store** (`storeId` of the event record): a listener that writes into another store fails loudly; a listener of an installation-wide event declares its system scope explicitly (D166). | **All stores for every listener:** never an under-read, but no isolation for asynchronous writes. |
| P66 | Which store-owned tables get a policy. | **Every store-owned table**, configuration included (catalog price lists and releases, printers, rooms and tables, warehouses), because §14 #9 names prices among what an operator must not change: 96 tables (D167). | **Operational and financial facts only** (orders, production, settlement, cash, fiscal, inventory movements, reporting): about 70 tables, but a store manager's write to another store's prices or printers is then caught only by `AccessPolicy`. |
| P67 | When the run is built. | **After R5's S63 has merged**, as its own run: S64 (prefactor, alone), then five slices in parallel, then S70 (D172). R5 is untouched. | **Interleaved with R5 Group 2:** earlier protection, but S64 changes the test harness and `application.yaml`, which R5 slices also touch, and RLS migrations merged before an R5 migration of a lower version break Flyway on persisted development databases. |
| P68 | How an RLS refusal is signalled. | **A coded 500, an ERROR log with the scope origin, a counter `store_scope_violations_total{kind}`, and an in-app alert `STORE_SCOPE_VIOLATION`** through the R5 `AlertSource` contract (D149, D170). | **Log and counter only**, no in-app alert: one class fewer; installations without Prometheus would see refusals only in the logs. |

---

## 1. Scope

| area | IN | out |
|---|---|---|
| Roles and provisioning | Three database roles (P61), one idempotent provisioning step run at every `docker compose up`, the migration from today's single role, restore and PITR handling, development and test parity | Per-employee database roles; connection-level authentication beyond the pool |
| Scope plumbing | Store scope from the `Actor` (D164); a checkout hook (D165); named system scopes for listeners, jobs, executors, gauges and bootstrap (D166); an enforcement switch for emergencies (D170) | Changing `AccessPolicy` decisions; per-permission scopes in the database |
| Policies | ENABLE + FORCE RLS and one canonical policy per protected table (96 now, 97 after R5); store-change triggers; a D42 probe function; `store_id` denormalised on `stock_balance` (D167–D169) | Column-level security; RLS on company-wide data (customers, suppliers, stock items, catalog items, loyalty, identity); a hash-chained audit |
| Proofs | A deterministic isolation suite, a coverage test against the real migrations, a startup check, plan tests under the application role, the full suite under the application role, the deploy drills (D171) | A penetration test |

**Module placement.** No new module (D1). The scope types live in `access` (they derive from the `Actor`, and every module may use them). The JDBC hook, the advisor and the startup check live in `config` (it may depend on `shared`, `access`, `audit`; nothing depends on it). The SQL-state classifier and the probe client live in `shared.persistence` (technical only, D14). The SQL functions live in the `shared` schema (block V1xxx). No new module edge: `ApplicationModules.verify()` is unchanged.

---

## 2. Decisions

### D162 ★ — What RLS protects, and what it does not

RLS is the **second** line of defence. `AccessPolicy` (D27, D42) stays the only authority for decisions and errors; RLS must never be the reason a correct request succeeds.

| RLS catches (an application bug on the pool) | RLS does not catch |
|---|---|
| A query that forgot its `store_id` filter, or filters by a wrong id | A wrong permission inside a store the actor holds (the scope is per actor, not per permission) |
| A resource read or written by an id from another store (IDOR across stores) | Company-wide data, which has no store (customers, suppliers, stock items, catalog items, loyalty, identity) |
| A write that inserts a row into, or moves a row to, another store | A compromised backend process or an infrastructure administrator (§10: "risco distinto"); the owner role bypasses RLS |
| SQL injected into a query on the application pool | Existence oracles through unique constraints and foreign keys (PostgreSQL checks them without RLS) |
| A listener, job or executor task that writes into a store it was not started for | |

**The scope is coarse on purpose.** It is the set of stores where the actor holds *any* permission (installation-wide = every store). Per-permission precision would duplicate `AccessPolicy` in SQL and become a second authority.

**Alternatives considered.** (a) Views per store or per actor: no write protection, and every query changes. (b) One schema per store: changes every query and every migration, and contradicts §2 ("schemas organizam módulos"). (c) A database role per store with `SET ROLE`: role explosion, and multi-store actors need role unions.

### D163 ★ — Database roles and their provisioning (P61)

**Roles** (cluster-wide objects, created by provisioning, never by Flyway):

| role | attributes | used by | owns |
|---|---|---|---|
| `restaurantpro` (existing) | superuser, the image's `POSTGRES_USER` (bootstrap superuser) | `backup.sh`, `basebackup.sh`, `restore.sh`, `pitr-restore.sh`, `verify-*.sh`, `mfa-break-glass.sh` (S56), the provisioning step. Never the backend. | the database and the `pg_trgm` extension |
| `restaurantpro_owner` (new) | `LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB BYPASSRLS`; `CREATE` on the database and on schema `public` | Flyway inside the backend (`spring.flyway.user`) | every schema, table, sequence, function and trigger of the application, and `flyway_schema_history` |
| `restaurantpro_app` (new) | `LOGIN NOSUPERUSER NOCREATEROLE NOCREATEDB NOBYPASSRLS NOINHERIT`; owns nothing | the Hikari pool | nothing |

Grants to `restaurantpro_app`: `USAGE` on the application schemas, `SELECT, INSERT, UPDATE, DELETE` on their tables, `USAGE, SELECT` on their sequences, and the same as **default privileges** `FOR ROLE restaurantpro_owner` (tables, sequences and schemas created by later migrations are granted automatically). No `TRUNCATE`, `REFERENCES`, `TRIGGER`, DDL or `MAINTAIN`. Because the owner has `BYPASSRLS`, migrations see every row (data fixes keep working); `FORCE ROW LEVEL SECURITY` is still set on every protected table, so a mistake that made the application role an owner would not switch RLS off.

**Policies are created `TO PUBLIC`** and the migrations never name a role: the superuser and the owner bypass RLS, so the policies bind exactly the application role, and a dump restores without the roles existing.

**Provisioning** is one idempotent SQL file, `deploy/postgres/provision-roles.sql` (psql variables `:'owner_password'`, `:'app_password'`), run by:
- **Compose:** a one-shot service `db-provision` (the pinned postgres image, `restart: "no"`, `depends_on: postgres: service_healthy`) runs `deploy/postgres/provision.sh` at every `docker compose up`. It reads `secrets/db_password` (superuser), `secrets/db_owner_password` and `secrets/db_app_password` with psql's `\getenv` (never on a command line), runs `SET log_statement = 'none'; SET log_min_error_statement = 'panic';` before `ALTER ROLE … PASSWORD`, then the file. `backend` gets `depends_on: db-provision: service_completed_successfully`. Running it at every start also re-syncs the role passwords after a PITR restore brought back older password hashes.
- **Tests:** `TestcontainersConfiguration` runs the same file once in the shared container, with passwords generated per JVM (never secrets).
- **Development:** `backend/compose.yaml` and `run-local.sh` run the same file, so a developer's backend also runs as `restaurantpro_app` (otherwise RLS would be silently off locally).

**What the file does, in order:** create the two roles when missing (attributes reasserted every run); set their passwords; grant `CREATE` on the database and schema `public` to the owner; **transfer ownership** of every application object still owned by `restaurantpro` to `restaurantpro_owner` (schemas, tables, sequences, functions, triggers' functions, `flyway_schema_history`; objects that belong to an extension are skipped; `REASSIGN OWNED` cannot be used because `restaurantpro` is the bootstrap superuser); grant the application privileges on everything that exists; set the default privileges. Each step is a no-op when already done.

**Migration from today's single role** (an R5 installation upgrading):
1. Backup (§11, as for every upgrade).
2. The operator creates `secrets/db_owner_password` and `secrets/db_app_password` (`openssl rand -hex 24`, mode 600; README).
3. `docker compose up -d`: `db-provision` creates the roles and transfers ownership; the backend starts with the new environment (`RESTAURANTPRO_DB_USERNAME=restaurantpro_app`, `RESTAURANTPRO_DB_PASSWORD_FILE=/run/secrets/db_app_password`, `RESTAURANTPRO_FLYWAY_USERNAME=restaurantpro_owner`, `RESTAURANTPRO_FLYWAY_PASSWORD_FILE=/run/secrets/db_owner_password`); Flyway applies the RLS migrations as the owner.
4. **Rollback** to the R5 image works without undoing anything: the R5 image connects as the superuser, which RLS never applies to.

**Backup, restore and PITR tooling run as the superuser** (unchanged): `pg_dump` and `pg_basebackup` see every row because superusers bypass RLS. A physical base backup and PITR restore the roles, ownership, grants and policies with the cluster. A logical restore (`restore.sh`, `verify-restore.sh`) runs `pg_restore --no-owner --no-privileges` and then the provisioning file, so it does not depend on the roles existing in the target cluster, and ownership and grants come out right. The dump keeps `ENABLE`/`FORCE ROW LEVEL SECURITY` and the policies.

**Startup check** (`config.persistence.StoreScopeStartupCheck`, after Flyway): the backend refuses to start when the pool's role is a superuser, has `BYPASSRLS`, or owns any application object, or when a table with a `store_id`, `from_store_id` or `to_store_id` column has neither RLS enabled and forced nor an entry in the unscoped registry (D167). This also catches a restore that lost its policies.

### D164 — The session variables and how actors map onto them (P63)

Three custom settings, always written together:

| setting | values | meaning |
|---|---|---|
| `app.all_stores` | `on`, `off`, `''` | `on` = every store, including rows of no store where a policy allows them |
| `app.store_ids` | a canonical `uuid[]` literal, lower case, sorted (`{…}`, `{}` = none), or `''` | the stores of the scope when `all_stores = off` |
| `app.scope_origin` | `account:<id>`, `employee:<id>@<terminal>`, `listener:<Class.method>`, `system:<NAME>`, `task:<origin>`, `none` | diagnostics only; quoted in errors and logs, never used by a policy |

**Fail closed.** `''` in either value means **unset**. Two `STABLE PARALLEL SAFE` functions in the `shared` schema read them: `shared.rls_all_stores()` and `shared.rls_store_ids()`. Each raises `SQLSTATE 'RS001'` ("store scope not set (origin …)") when its value is unset (P63). An explicit empty scope (`off`, `{}`) is not an error: it sees no store rows. A setting that was never set reads as NULL, and one whose `SET LOCAL` ended reads as `''` (verified on 18.6); both are unset.

**Mapping** (`access.StoreScope.of(Actor)`, pure, unit-tested):

| context | `all_stores` | `store_ids` |
|---|---|---|
| BackOffice account holding at least one installation-wide authority (`PERM_X`) | `on` | `{}` |
| BackOffice account with only store-scoped authorities (`PERM_X@store`) | `off` | those stores |
| Account that must change its password (no authority, S10) | `off` | `{}` |
| Support account (D83, D145) | as any account, from its roles (support roles withhold the D83 permissions, not stores) | |
| POS employee session (D27 amended, D39) | `off` | `{terminal store}` when it holds any authority there, else `{}` |
| Approver PIN verification (D28, D40) | the operator's scope (it runs on the operator's request) | |
| Listener (D166) | `off` | `{event.storeId}` |
| Named system scope `ALL` / `NONE` (D166) | `on` / `off` | `{}` |
| Executor task | the scope captured when the task was submitted | |
| Nothing (anonymous request, login before authentication, an unknown thread) | `''` | `''` |
| Enforcement `PERMISSIVE` (D170) | `on` | `{}` |

Resolution order (`access.StoreScopes.current()`): the innermost explicit system or listener scope, else the authenticated `Actor`, else the fallback (production: unset; tests: see D171). **Alternatives:** (a) one setting with a `*` sentinel for all stores: shorter, but an explicit flag cannot be confused with a malformed list; (b) a comma-separated text list: needs parsing in every policy; the `uuid[]` cast validates the format once per statement.

### D165 ★ — Where the scope is applied: at connection checkout (P62)

**Decision.** `config.persistence.StoreScopedDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource` wraps the Hikari `DataSource` bean (through a `BeanPostProcessor`, so the JPA `EntityManagerFactory`, `JdbcTemplate`, `JdbcClient`, Spring Session JDBC and the Spring Modulith JPA event publication registry all receive it). On every `getConnection()` it runs one statement before returning the connection:

```sql
SELECT set_config('app.all_stores', ?, false), set_config('app.store_ids', ?, false), set_config('app.scope_origin', ?, false)
```

with the values of `StoreScopes.current()`. `getConnection(user, password)` throws `UnsupportedOperationException`. When the statement fails, the connection is evicted (`HikariDataSource.evictConnection`), never handed out.

**Why it covers every path.** `JpaTransactionManager` acquires the connection at transaction begin and exposes it to `JdbcTemplate`/`JdbcClient` (one connection per transaction), so a transaction is scoped once, at its start. `REQUIRES_NEW` (`PinVerification`, `DocumentIssueService`, every `@ApplicationModuleListener`) checks out a new connection, which is scoped from the thread's current scope. `readOnly` transactions are scoped the same way (`set_config` runs before `SET TRANSACTION READ ONLY`, and is allowed in read-only transactions anyway). Statements outside any transaction (autocommit `JdbcClient` calls, Spring Data's own short transactions) check out and are scoped. The event publication registry writes in the caller's transaction (same connection) and completes in the listener's (scoped by D166).

**Why it cannot leak across pooled connections.** Every checkout overwrites all three settings unconditionally, before any statement of the borrower, so a stale value left on an idle connection is never read by its next borrower. The statement runs in autocommit (Hikari's default `auto-commit: true`; the hook asserts `getAutoCommit()` and refuses otherwise), so a later rollback cannot revert it (verified: a session-level `set_config` survives a later rolled-back transaction). Hikari's own reset on return does not touch custom settings, and nothing else obtains connections: an ArchUnit rule allows `com.zaxxer.hikari` only in `config`.

**A scope cannot change inside a connection.** `StoreScopes.run(...)` throws `IllegalStateException` when a transaction is active, because the code inside would join the outer transaction and its connection, silently keeping the outer scope (the same guard as `ExternalCalls.requireNoTransaction()`, D48).

**Queries outside a transaction** are allowed and scoped; when there is no scope, a policy table raises `RS001` (P63). Nothing is forbidden that works today.

**Cost:** one extra round trip per checkout (a transaction or an autocommit statement); on the local server this is well under a millisecond, inside the §12 budget.

**Alternatives considered.**
- **`SET LOCAL` in a `TransactionExecutionListener.afterBegin`** (Spring Framework 6.1+, applied by Boot to the transaction manager): the user's literal wording, no session state at all. Autocommit statements get no scope and raise `RS001`; production code with such reads must move into a transaction. Same tests otherwise. A sound second choice (P62).
- **A custom `JpaTransactionManager.doBegin`:** the same as the listener, with a subclass to maintain.
- **AOP around `@Transactional`:** misses `TransactionTemplate` (used by 8 services), Spring Data's default transactions and autocommit statements.
- **Hikari `connectionInitSql`:** runs once per physical connection, not per borrower; useless for a per-actor scope.

### D166 — Actor-less contexts: named, explicit system scopes (P65)

No `BYPASSRLS` on the application role: it would switch RLS off for every request too, since requests and jobs share the pool. A second pool with a privileged role for jobs was rejected: listeners run inside the Spring Modulith machinery on the one `DataSource`, and the jobs' writes would be unprotected.

**`access.SystemScope`** is an enum: the reviewed allow-list of every actor-less scope, each with its reach (`ALL` = `all_stores on`; `NONE` = an explicit empty scope) and its reason. **`@access.SystemScoped(SystemScope.X)`** on a method runs it in that scope; `StoreScopes.run/call(SystemScope, …)` does it in code. An infrastructure-role Spring AOP advisor in `config` (`StoreScopeAdvisor`, `@Role(ROLE_INFRASTRUCTURE)`, `MethodInterceptor`, no AspectJ dependency) applies `@SystemScoped` and the listener rule below, **ordered before the transaction interceptor** (`Ordered.HIGHEST_PRECEDENCE + 10`) so the scope is in place before the listener's `REQUIRES_NEW` transaction checks out its connection. `@Async` dispatch runs first (it is added before existing advisors), so the scope is set on the executor thread.

| scope | reach | entry points today |
|---|---|---|
| `BOOTSTRAP` | ALL | `organization.service.CompanyBootstrapRunner.run`, `identity.service.AdminBootstrapService` (listener of `CompanyBootstrapped`, which has no store) |
| `PRINT_WORKER` | ALL | `production.service.PrintWorker.poll` (+ its sender tasks, below) |
| `PAYMENT_RECOVERY` | ALL | `settlement.service.AttemptSweeper`, `InDoubtResolver`, `InDoubtGauge.refresh`, `CreditChargeSweeper` |
| `RESERVATIONS` | ALL | `dining.service.ReservationNoShowJob` |
| `STOCK_RECONCILE` | ALL | `inventory.service.StockBalanceReconciliationJob` |
| `REPORTING_RECOVERY` | ALL | `reporting.service.RebuildService.abandonInterruptedRuns` (`ApplicationReadyEvent`) |
| `METRICS` | ALL | gauges that query the database on the scrape thread: `production.service.ProductionMetrics` (`print_jobs`). Without it, a token scrape (no actor) would raise, and a session scrape would cache one actor's view for everyone. |
| `ALERTS` | ALL | the R5 alert aggregation in `config/alerts` and every `AlertSource` it calls (D149 computes installation-wide, then filters by `AccessScope`) |
| `EVENT_MAINTENANCE` | NONE | `shared.events.EventPublicationRecovery` (2 methods), `EventPublicationStats` |
| `IDENTITY_MAINTENANCE` | NONE | `identity.service.LoginThrottling`, `SupportGrantExpiry` |
| `HOUSEKEEPING` | NONE | `shared.idempotency.IdempotencyRetention`, `shared.notify.ChangeNotifier.heartbeat` |

That is all 14 `@Scheduled` methods, the runner, the readiness hook and the DB-backed gauge. `NONE` scopes touch only unscoped tables; if one ever reads a store table it sees nothing, so the gap shows in its own test.

**Listeners (P65).** For every `@ApplicationModuleListener` method the advisor reads the event's record component `UUID storeId()` and runs the method in `listener` scope `{storeId}`. All 21 listener methods today receive events that carry `storeId` (`OrderBatchConfirmed`, `OrderLinesVoided`, `SubtotalRequested`, `FiscalDocumentIssued`, `FiscalDocumentReprintRequested`, `PaymentRecorded`, `RefundRecorded`, `CreditChargePosted`, `ReceiptSlipRequested`, `DeliveryDispatched`, `StockMovementsPosted`, `ConsumptionWasteRecorded`, `StoreDeactivated`, `TerminalRevoked`, plus R5's `CashDrawerKickRequested`) except `CompanyBootstrapped` (`@SystemScoped(BOOTSTRAP)`). A listener whose event has no `storeId`, or whose `storeId` is null, fails before its transaction unless the method carries `@SystemScoped`. Republication at restart and the D88 recovery go through the same proxies, so they get the same scope.

**Executor tasks.** `StoreScopes.propagate(Runnable)` captures the resolved scope at submission and runs the task in it, on the worker thread, with origin `task:<captured origin>`. Used at the three hand-made submissions: `PrintWorker` sender tasks (captures `PRINT_WORKER`), `settlement.service.PaymentSubmitter` (captures the submitting POS session's store scope; after a restart `AttemptSweeper` resubmits under `PAYMENT_RECOVERY`), `reporting.service.RebuildService` (captures the requesting account's scope after commit; the account holds `REPORT_REBUILD` in that store). `production.adapter.EscPosTcpTransport`'s watchdog, `ProviderCalls` and the `ChangeNotifier` sender touch no database.

**"Audited", concretely.** A system scope is not an `audit_event` row per job tick (that is noise; §10's audit is for business actions, and writes inside these scopes already record `recordAsSystem`, D41). It is audited in the code: the enum is the allow-list, `SystemScopeRegistryTest` compares the set of `(class.method → scope)` with a checked-in expectation, so a new `ALL` is a visible diff in review, and every database error names the origin. ArchUnit rules: every `@Scheduled` method, `ApplicationRunner.run` and `@EventListener(ApplicationReadyEvent)` method is `@SystemScoped`; every `@ApplicationModuleListener` parameter type has a `UUID storeId` component or the method is `@SystemScoped`; a class calling `java.util.concurrent.Executor.execute` or `ExecutorService.submit` also calls `StoreScopes.propagate` (allow-list: the three non-database executors above).

**Alternatives considered.** (a) All stores for every listener: no isolation of asynchronous writes (P65). (b) An `implements StoreScopedEvent` marker on each event: explicit, but it touches every module's event records; the record component plus the ArchUnit rule gives the same guarantee. (c) `BYPASSRLS` for jobs: rejected above.

### D167 — Table coverage (P64, P66)

Five table classes. A table belongs to exactly one; the coverage test (D171) fails on a table in none.

- **STORE**: the row belongs to one store, `store_id NOT NULL`. Policy on `store_id`.
- **STORE_OR_ALL**: `store_id` NULL means "every store" (a company-wide quick page or automatic item).
- **CHILD**: no `store_id`; it belongs to a parent of the same module through an in-module foreign key. Policy by parent lookup.
- **SPECIAL**: `audit.audit_event` (reads scoped, inserts open) and `inventory.stock_transfer` (two stores).
- **UNSCOPED**: no policy, registered with a reason in `shared.rls_unscoped_table` (D168). Company-wide data, the identity authority, and infrastructure read before an actor exists.

**Coverage by module** (154 tables in the migrations at the baseline; `flyway_schema_history` is UNSCOPED too and not counted):

| module | STORE | STORE_OR_ALL | CHILD → parent | SPECIAL | UNSCOPED (reason) |
|---|---|---|---|---|---|
| shared / public (5) | | | | | `event_publication`, `event_publication_archive` (Modulith registry, read at startup), `idempotency_record` (keyed by principal, D38), `spring_session`, `spring_session_attributes` (read before authentication) |
| audit (1) | | | | `audit_event` | |
| organization (3) | | | | | `company_profile`, `store`, `terminal` (read before login by device token) |
| identity (9) | | | | | `role`, `role_permission`, `employee`, `employee_store_assignment`, `user_account`, `user_account_role`, `terminal_session`, `login_throttle`, `support_grant` (the authority the scope is derived from; read before an actor exists) |
| dining (6) | `room_area`, `restaurant_table`, `table_occupation`, `reservation`, `reservation_table`, `dining_settings` | | | | |
| catalog (19) | `price_list`, `configuration_release`, `item_availability`, `daily_dish` | `quick_page`, `automatic_item` | `item_price` → `price_list`; `quick_page_entry` → `quick_page` | | `unit`, `family`, `item`, `item_variant`, `barcode`, `tax_category`, `tax_rate`, `menu_group`, `menu_option`, `macro`, `macro_entry` (company-wide catalog) |
| customers (8) | | | | | `customer`, `customer_phone`, `customer_address`, `credit_account`, `credit_reservation`, `credit_limit_change`, `credit_settlement`, `credit_ledger_entry` (company-wide, P38; its `store_id` is the origin of an entry, not its owner) |
| promotions (12) | `discount_limits`, `promotion_release` | | | | `promotion`, `promotion_scope`, `promotion_store`, `promotion_window`, `loyalty_program_version`, `loyalty_program_scope`, `loyalty_reward`, `loyalty_account`, `loyalty_allocation`, `loyalty_point_entry` (company-wide program, ADR 0030; `store_id` is an origin) |
| orders (12) | `order_header`, `order_batch`, `order_transfer`, `delivery`, `delivery_zone` | | `order_line`, `order_line_component`, `order_line_price_adjustment`, `order_line_split`, `order_line_void` → `order_header`; `order_transfer_line` → `order_transfer`; `delivery_event` → `delivery` | | |
| production (5) | `printer`, `destination_route`, `terminal_printer`, `production_ticket`, `print_job` | | | | |
| cash (8) | `cash_register`, `cash_day`, `cash_day_reopening`, `cash_session`, `cash_movement`, `cash_closing` | | `cash_session_count` → `cash_session`; `cash_closing_line` → `cash_closing` | | |
| settlement (14) | `payment_attempt`, `payment`, `refund`, `bill_partition`, `credit_charge`, `reconciliation_batch`, `reconciliation_entry`, `tax_rounding_adjustment` | | `payment_allocation` → `payment`; `bill_partition_line` → `bill_partition`; `reconciliation_resolution` → `reconciliation_entry`; `tax_correction_resolution` → `tax_rounding_adjustment` | | `payment_method` (company-wide configuration), `order_settlement` (the D51 lock row: an order id and a timestamp, no business data; a parent lookup would cross into `orders`) |
| fiscal (6) | `fiscal_series`, `fiscal_document` | | `fiscal_document_line`, `fiscal_document_tax`, `fiscal_document_payment`, `fiscal_document_print` → `fiscal_document` | | |
| inventory (34) | `warehouse` (reads open, see D168), `consumption_route`, `inventory_activation`, `stock_movement`, `stock_balance` (**new `store_id`**, P64), `balance_check`, `consumed_line`, `line_void`, `waste_record`, `consumption_exception`, `cost_variance`, `preparation`, `purchase_order`, `goods_receipt`, `supplier_return`, `stock_count` | | `stock_level` → `warehouse`; `purchase_order_line` → `purchase_order`; `goods_receipt_line`, `receipt_cost_correction` → `goods_receipt`; `supplier_return_line` → `supplier_return`; `stock_count_line` → `stock_count`; `stock_transfer_line` → `stock_transfer` | `stock_transfer` | `stock_unit`, `stock_item`, `stock_item_pack`, `supplier`, `supplier_item`, `recipe`, `recipe_version`, `recipe_ingredient`, `consumption_policy_version`, `consumption_policy_rule` (company-wide, r4 §13) |
| reporting (12) | `sales_record`, `sales_record_line`, `sales_record_tax`, `payment_record`, `credit_record`, `void_record`, `discount_record`, `offer_cost_record`, `consumption_record`, `consumption_waste_record`, `cost_variance_record`, `rebuild_run` | | | | |
| **total 154** | **66** | **2** | **26** | **2** | **58** |

**96 tables get a policy** (62 % of the tables). Of the 74 tables with a `store_id` column, 68 get a policy and 6 are UNSCOPED with the reason above (`employee_store_assignment`, `terminal_session`, `terminal`, `credit_ledger_entry`, `loyalty_point_entry`, `promotion_store`). `stock_transfer` has `from_store_id`/`to_store_id` instead. **R5 adds** `catalog.store_item_exclusion` (STORE, S58) and `identity.mfa_factor`, `mfa_recovery_code`, `mfa_enrolment_code`, `external_identity` (UNSCOPED, S56, S57): 159 tables, 97 with a policy.

**Child tables (P64).** Parent lookup is chosen because it needs no data migration, no entity change and no update of an immutable row: 21 of the 26 children are append-only or frozen by triggers (`orders` V6007, `fiscal` V10005, `settlement`, `cash`), and D160's rule "no migration updates an immutable row" stays intact. The cost is a per-row primary-key probe of the parent. The prototype on PostgreSQL 18.6 (100 000 orders in 20 stores, 3 lines each, median of 9 runs on a loaded laptop) measured a 15 000-line join for one store at about 520 ms with the parent lookup, 100 ms with a denormalised `store_id` and 70 ms without RLS; one order's lines stayed under 3 ms. The only child that is both read in bulk on a request path (stock valuation by store) and hot (locked on every consumption) is **`inventory.stock_balance`**, a mutable projection whose writer already knows the store (from the movement). It gets a denormalised `store_id NOT NULL`, backfilled from `warehouse`, with a composite foreign key `(warehouse_id, store_id) → warehouse (id, store_id)` so it can never disagree with its warehouse. The other bulk child reads (report rebuild through `FiscalApi` facts) run in the background. S70 measures the request-path ones with the load smoke (A77).

**Append-only and audit tables** are covered like the others; their immutability triggers stay, and RLS adds only the row filter. **`event_publication`**, **`spring_session`** and **`idempotency_record`** stay UNSCOPED: Spring Session reads before authentication, the registry is read at startup and by the D88 recovery, and idempotency records are keyed by the principal (D38). Their contents never reach a client except a replayed response to the same principal.

### D168 — Policies

All policies are generated by helper functions in `shared` (block V1008), so every protected table carries the same canonical text, and the coverage test compares the stored expression with it. `VIS(c)` below abbreviates `((SELECT shared.rls_all_stores()) OR c = ANY ((SELECT shared.rls_store_ids())::uuid[]))`. The `(SELECT …)` wrappers make each function an InitPlan, evaluated once per statement, not once per row (verified with `EXPLAIN`).

| helper (owner runs it in a migration) | what it creates on the table |
|---|---|
| `shared.rls_protect_store_table(t regclass)` | `ENABLE` + `FORCE ROW LEVEL SECURITY`; policy `store_scope FOR ALL TO PUBLIC USING (VIS(store_id)) WITH CHECK (VIS(store_id))`; trigger `store_scope_fixed BEFORE UPDATE OF store_id … WHEN (OLD.store_id IS DISTINCT FROM NEW.store_id) EXECUTE FUNCTION shared.rls_reject_scope_change()` (`RS002`) |
| `shared.rls_protect_store_or_all_table(t)` | as above, with `USING (store_id IS NULL OR VIS(store_id))` and `WITH CHECK (VIS(store_id))`: everyone in scope reads the company-wide rows; only an all-stores scope writes them (`NULL = ANY(…)` is not true) |
| `shared.rls_protect_child_table(t, fk_column, parent regclass, parent_key)` | `ENABLE` + `FORCE`; policy `parent_scope FOR ALL USING (EXISTS (SELECT 1 FROM parent p WHERE p.parent_key = t.fk_column AND VIS(p.store_id)))`, same `WITH CHECK`; trigger `parent_fixed BEFORE UPDATE OF fk_column … WHEN (OLD IS DISTINCT FROM NEW)` → `RS002`. The store is checked explicitly on the parent, not only through the parent's own policy (`warehouse` reads are open). |
| `shared.rls_register_unscoped(t, reason text)` | a row in `shared.rls_unscoped_table(table_name regclass PRIMARY KEY, reason text NOT NULL CHECK (btrim(reason) <> ''))` |

**Reads, inserts, updates, deletes.** One `FOR ALL` policy: `USING` filters `SELECT`, the old row of `UPDATE`, `DELETE` and `SELECT … FOR UPDATE`; `WITH CHECK` checks the new row of `INSERT` and `UPDATE`, so a write never lands outside the scope. **A write never moves a row to another store**, also for an all-stores or system scope: the `RS002` trigger rejects any change of `store_id` (or of a child's parent key) before the policy is checked. No production SQL updates these columns today (603 entity columns are `updatable = false`, and no `UPDATE … SET store_id` or parent-key update exists), so the trigger changes no behaviour. Prototype results on 18.6: unset scope → `RS001`; an insert into another store → `42501 new row violates row-level security policy`; an update by id of another store's row → `UPDATE 0`; a store move by a single-store or all-stores scope → `RS002`; a child pointing at another store's parent → `42501`, although the foreign key itself passes (PostgreSQL checks foreign keys without RLS). `INSERT … ON CONFLICT DO NOTHING` (reporting projections) needs only `WITH CHECK`; `ON CONFLICT DO UPDATE` also needs the existing row visible, which it is within the same store.

**Special tables.**
- `audit.audit_event`: `FOR SELECT USING ((SELECT shared.rls_all_stores()) OR store_id = ANY (…))`, so store-less rows (system, company) are visible only to an all-stores scope, as D82 already does in the application; `FOR INSERT WITH CHECK (true)`, because login, logout and lockouts are audited before an actor exists (D20, D41); no `UPDATE`/`DELETE` policy (the D18 trigger refuses them anyway).
- `inventory.stock_transfer`: `USING (VIS(from_store_id) OR VIS(to_store_id))`, same `WITH CHECK`, and `RS002` on a change of either column. A store-A shipper and a store-B receiver each see the transfer and its lines (S42, D120).
- `inventory.warehouse`: `FOR SELECT USING (true)`; `INSERT`/`UPDATE`/`DELETE` policies with `VIS(store_id)`. A transfer names a warehouse of another store (`TransferService` resolves its store), and warehouse names and codes are configuration, not facts.

**D42 probe** (amends nothing in D42's behaviour). D42 (b) reads only a resource's `storeId`, answers 404 when absent, then `require` answers 403. Under RLS, another store's row would read as absent and turn the 403 into a 404, so the 403-bug visibility D42 chose would be lost. **`shared.store_of(t regclass, id uuid) RETURNS uuid`** is the one `SECURITY DEFINER` function (owner, `SET search_path = pg_catalog`): it refuses a table without RLS or without a `store_id` column, finds the table's single-column primary key in the catalog, and returns only `store_id` of that row, or NULL. It reveals what D42 (b) already reveals, and nothing else. `shared.persistence.StoreProbe.storeOf(String table, UUID id) → Optional<UUID>` calls it. The ~40 repository probe methods (`select o.storeId from Order o where o.id = :id` and their kin, about 68 call sites) switch to it in their module's slice; `inventory.stock_transfer` gets `inventory.stock_transfer_stores(id) RETURNS TABLE(from_store_id uuid, to_store_id uuid)` likewise.

**Cross-store operations.** Inter-store stock transfers work as above. Order transfers between stores are outside the normal flow (§8.2) and stay refused. Reports across stores run with the actor's scope (the union of its stores, or all), and `AccessPolicy.scopeOf` keeps filtering per permission. Customers, credit, loyalty, suppliers and stock items are company-wide and unaffected.

**SECURITY DEFINER, triggers and foreign keys.**
- No `SECURITY DEFINER` function may read or write a protected table, except `shared.store_of` and `inventory.stock_transfer_stores` (coverage test allow-list). A definer function runs as the owner, who bypasses RLS. Today there is none. R5's console functions (`identity.mfa_break_glass_reset`, `audit.record_console_event`, S56) touch only unscoped tables or the open audit insert; S64 revokes `EXECUTE` on them from `PUBLIC`, so only the superuser console calls them.
- Trigger functions run as the invoking role, so their reads are filtered too. The existing ones (`settlement.check_refund_within_payment`, `check_payment_allocation_sum`, `check_payment_allocated`, the `customers` and `fiscal` guards) read rows of the same store as the row being written, or unscoped tables. The full suite under the application role proves it (A78).
- In-module foreign keys and unique constraints are checked without RLS. The `CHILD` policy turns "the parent exists" into "the parent exists in my scope", which is the §8 "integridade das referências entre lojas" inside a module. A unique violation can reveal that a key exists in another store; within one company that is accepted (D42 accepted the same).
- Policies never reference another module's schema (D6); `order_settlement` stays UNSCOPED for that reason.

### D169 — Indexes

**No index is needed to serve a policy.** The policy is an InitPlan filter applied on top of each query's own predicates; the query's index still drives the scan (prototype: `Index Cond` from the query's `store_id`/`status`, `Filter` = the policy). Every store-scoped query already filters by store or reaches rows by primary key, and the CHILD lookup uses the parent's primary key. **Missing indexes: one**, `inventory.warehouse_id_store_uk UNIQUE (id, store_id)`, required by the composite foreign key of `stock_balance` (D167).

**The real plan risk is leakproofness.** Under RLS, PostgreSQL uses a query's own predicate as an index condition only when its operator is leakproof. On 18.6, `uuid_eq`, `texteq`, `bpchareq`, `int4eq`, `int8eq`, `booleq`, `date` and `timestamptz` comparisons are leakproof; **`numeric_eq`, `jsonb_eq` and `textlike` are not**. Verified: a `(store_id, qty numeric)` index keeps `store_id` in `Index Cond` but moves `qty = 2` to the filter under RLS. Each policy slice lists its module's queries that rely on an index for a `numeric`, `jsonb` or `LIKE`/`ILIKE` predicate on a protected table (the trigram customer search is on an unscoped table) and proves its plan with the R3 pattern. Granting `LEAKPROOF` to built-in operators would need the superuser and is rejected.

### D170 — Errors, signals and the emergency switch (P68)

Application authorization stays primary: 404, then 403 (D42), then the business rules. RLS refusing something the application allowed is a bug.

| database outcome | SQLSTATE | API (ProblemDetail, `code`) | signal |
|---|---|---|---|
| A policy table touched with no scope | `RS001` | 500 `STORE_SCOPE_MISSING` | ERROR log with the origin; `store_scope_violations_total{kind="scope_missing"}` |
| `WITH CHECK` refused a written row | `42501`, message names "row-level security" | 500 `STORE_SCOPE_VIOLATION` | ERROR; `kind="write_refused"` |
| A store or parent key changed | `RS002` | 500 `STORE_CHANGE_REFUSED` | ERROR; `kind="store_change"` |
| A read filtered, an `UPDATE`/`DELETE` matching 0 rows | none | whatever the application concludes: 404, an empty list, or 409 from a `@Version` update | none at runtime (indistinguishable from absence); caught by the suites (D171) |

The response carries only the code and the correlation id, never a store id or the SQL. 5xx is never stored by the Idempotency-Key mechanism (D47), so a retry after the fix runs once. Two catch points classify: `shared.web.GlobalExceptionHandler` (requests; it reads the SQL state through `shared.persistence.StoreScopeViolations.classify(Throwable)`), and the `StoreScopeAdvisor` and `StoreScopes.propagate` (listeners, jobs, tasks; the listener then fails, its publication stays incomplete, D88 retries it and the R5 `EVENT_PUBLICATIONS_FAILED` alert fires). P68: an `AlertSource` in `config` reports `STORE_SCOPE_VIOLATION` (CRITICAL) while any violation happened in the last 24 h, by kind, with no ids in labels (D149, §12).

**Emergency switch.** `restaurantpro.store-scope.enforcement` = `ENFORCE` (default) or `PERMISSIVE`. `PERMISSIVE` makes the hook write `all_stores = on` on every checkout: RLS stops filtering without any DDL, and is restored by a restart with `ENFORCE`. It exists so a false positive in production (say, a listener scoped wrongly) cannot stop sales (§11 "plano de recuperação"). At startup the backend logs ERROR, raises the `STORE_SCOPE_PERMISSIVE` alert (CRITICAL) and writes one `recordAsSystem` audit row `STORE_SCOPE_PERMISSIVE_STARTED`. It does not touch `AccessPolicy`. Env `RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT`, not in `.env.example`; README runbook only.

### D171 — Tests and proofs

**Harness (S64).**
- `TestcontainersConfiguration` provisions the shared container with `deploy/postgres/provision-roles.sql`. `JdbcConnectionDetails` → `restaurantpro_app`; `FlywayConnectionDetails` → `restaurantpro_owner`. Every test then runs production code as the application role.
- **`OwnerDatabase`** (test bean): `jdbc()` and `tx()` over a two-connection owner pool, for privileged fixtures (`TRUNCATE`, `ALTER TABLE … DISABLE TRIGGER`, `ANALYZE`, DDL, `DROP`). About 30 test files use those today and move to it.
- **Fixture fallback:** a JUnit extension (registered for every test through `junit-platform.properties`) marks the JUnit worker thread during each test. A test `StoreScopeFallback` bean returns `ALL` (origin `test-fixture`) **only on a marked thread with neither an actor nor an explicit scope**. Unmarked threads (listeners, schedulers, executors) get production semantics (unset). So fixture SQL on the test thread keeps working, while a test with an `Actor` in its security context exercises real policies, and every asynchronous path is strict. A fixture that must seed another store while an actor is in the security context uses `StoreScopeTestSupport.asFixture(() -> …)` (clears the actor for the call, so the fallback applies) or `OwnerDatabase`. No test-only constant is added to the production `SystemScope` enum.
- **`@StrictStoreScope`** on a test class turns the fallback off, so the test thread is unset too (the isolation suites below).

**Deterministic suites** (real PostgreSQL, latches, no sleeps):

| test | proves |
|---|---|
| `StoreScopeTest` (unit) | the D164 mapping for every row of the table, canonical array text |
| `StoreScopedDataSourceTest` | every checkout writes all three settings; a failing `set_config` evicts the connection; `auto-commit=false` is refused; `getConnection(user, pw)` throws |
| `StoreScopePoolReuseTest` | pool of size 1: actor A, actor B, a system scope and an anonymous call alternate on the same physical connection (same `pg_backend_pid()`), each sees exactly its rows, the anonymous call gets `RS001`; a rolled-back transaction does not revert the scope; `StoreScopes.run` inside a transaction throws |
| `StoreScopeConcurrencyTest` | a listener for store A and a request for store B run at once (latch) and each writes only its store |
| `<Module>RowLevelSecurityTest` (one per policy slice) | per table class: scoped read, insert into another store → 42501, store move → RS002 (also for all-stores), child to another store's parent → 42501, unset → RS001, store-or-all rows read by every scope and written only by all-stores; `shared.store_of` returns another store's id for the D42 403 |
| `ActorlessStoreScopeTest` (`@StrictStoreScope`) | each of the 14 jobs, the runner, the readiness hook, each listener (forced redelivery included), the print sender, the payment submitter, the rebuild runner and the `print_jobs` gauge under a token scrape completes with its scope and with no `RS001` |
| `RowLevelSecurityCoverageTest` | against the real migrations: every application table is protected (ENABLE, FORCE, the canonical policy text, the RS002 trigger) or registered UNSCOPED with a reason; every `store_id`/`from_store_id`/`to_store_id` table is one of them; no `SECURITY DEFINER` function outside the allow-list; the application role is not superuser, has no `BYPASSRLS`, owns nothing and has no `TRUNCATE`/`REFERENCES`/`TRIGGER`. Per-module assertions in each slice; the all-schemas assertion in S70. |
| `StoreScopeStartupCheckTest` | the backend refuses to start as a superuser, as a `BYPASSRLS` role, as an owner, or on a database where a `store_id` table lost its RLS |
| `SystemScopeRegistryTest` | the `(class.method → SystemScope)` set equals the checked-in expectation |
| ArchUnit (`ArchitectureRules` + `ArchitectureRulesFireTest` fixtures) | `scheduledEntryPointsAreSystemScoped`, `listenersHaveAStoreOrASystemScope`, `executorSubmissionsPropagateTheStoreScope`, `onlyConfigUsesHikari` |
| `CodedErrorContractTest` | `STORE_SCOPE_MISSING`, `STORE_SCOPE_VIOLATION`, `STORE_CHANGE_REFUSED` |
| `ModularityTests` | `verify()` unchanged (no new edge) |

**Performance.** The R3 plan tests (`ReportPlanTest`, `LoyaltyBalancePlanTest`, `CustomerSearchPlanTest`) keep their assertions and now run `EXPLAIN` as the application role in a store scope (seeding and `ANALYZE` through `OwnerDatabase`). Each policy slice adds plan tests for its hot queries (S66: open orders by table and status, lines of an order; S67: payments of an order, the open cash session, the fiscal series lock; S68: `stock_balance` by warehouse and valuation by store, movements by store and day; S69: the 31-day report queries, the catalog release of a store, audit by store and action). S70 runs `deploy/load` (the R4 load smoke) under the application role: §12 p95 < 500 ms for simple local commands and queries.

**Full suite.** `cd backend && rtk mvn clean test` green with 0 skipped after every slice, with the application role (about 2 700 tests).

**Deploy drills (S65, S70).** `test-backup-restore.sh` (restore into a fresh container, then provisioning, then the backend starts and answers as the application role), `test-pitr.sh --quick` (roles, ownership and policies survive PITR; the passwords re-sync), `test-two-installations.sh` (A's owner and application passwords do not open B; B's dump is byte-identical; each backend's pool role is `restaurantpro_app`).

### D172 — Rollout, sequencing and Flyway blocks (P67)

**After R5's S63** (P67). R5 is mid-build (Group 1: S56, S58, S59, S61; Group 2: S57, S60). This run touches migrations in eleven module blocks, the test harness, `application.yaml`, Compose and every scheduled job and listener; interleaving would collide with R5 write sets and Flyway versions. RLS also needs R5's tables (S58's `store_item_exclusion`, S56/S57's identity tables) and the R5 alert code to exist, so they are covered once.

Slices (PRD): **S64** prefactor alone; **Group 1** in parallel: **S65** deploy, **S66** orders + dining + production, **S67** settlement + cash + fiscal, **S68** inventory, **S69** catalog + promotions + reporting + audit; **S70** integrate alone.

**Flyway versions** (D5, D61; numbered above every R5 reservation of D160, so the order is right on persisted databases):

| block | version | slice | content |
|---|---|---|---|
| shared | `V1008` | S64 | scope functions, `rls_reject_scope_change`, the four helpers, `rls_unscoped_table`, `store_of`; registers the shared and public tables |
| organization | `V2004` | S64 | registers 3 tables |
| identity | `V3015` | S64 | registers 13 tables (9 + R5's 4); revokes `EXECUTE` on `identity.mfa_break_glass_reset` from `PUBLIC` |
| customers | `V11004` | S64 | registers 8 tables |
| audit | `V1106` | S69 | `audit_event` policies; revokes `EXECUTE` on `audit.record_console_event` from `PUBLIC` |
| dining | `V4007` | S66 | 6 tables |
| catalog | `V5007` | S69 | 8 tables protected (with R5's `store_item_exclusion`), 11 registered |
| orders | `V6030` | S66 | 12 tables |
| production | `V7010` | S66 | 5 tables |
| cash | `V8011` (`V8010` stays reserved for R5's S61) | S67 | 8 tables |
| settlement | `V9060` | S67 | 12 protected, 2 registered |
| fiscal | `V10012` | S67 | 6 tables |
| promotions | `V12023` | S69 | 2 protected, 10 registered |
| inventory | `V13080` (`stock_balance.store_id` backfill + composite FK), `V13081` (policies) | S68 | 24 protected, 10 registered |
| reporting | `V14006` | S69 | 12 tables |

No cross-module FK (D6). No migration updates an immutable row: the only backfill is `stock_balance`, a mutable projection.

### D173 — Named races and their proofs

| race / failure | prevention | proof |
|---|---|---|
| Two borrowers of one pooled connection, store A then store B | every checkout overwrites all settings before the borrower's first statement | IT `StoreScopePoolReuseTest` |
| `set_config` fails on a broken connection | the connection is evicted, never handed out | `StoreScopedDataSourceTest` |
| A transaction rolls back after the scope was set | the scope is set in autocommit before the transaction; `auto-commit=false` refused | IT `StoreScopePoolReuseTest`, `StoreScopedDataSourceTest` |
| A listener for A and a request for B at the same moment | the scope is a thread-local value captured at checkout; one connection per transaction | IT `StoreScopeConcurrencyTest` (latch) |
| `StoreScopes.run` inside an active transaction (would keep the outer scope) | throws `IllegalStateException` | unit + IT |
| An all-stores admin and a system job both rewrite a row's `store_id` | `RS002` trigger, regardless of scope | IT `<Module>RowLevelSecurityTest` |
| A gauge scraped with a token, then with a store-scoped session, sharing a 1 s cache | the gauge runs in `METRICS` (ALL), whoever scrapes | IT `ActorlessStoreScopeTest` |

**Every §7 item still holds.** No lock order changes (D132): the scope statement runs before the transaction and takes no lock. External calls still run with no transaction (D48); a submitter task carries its captured scope into its short recording transactions. Events still survive a crash (D19); a listener refused by RLS stays incomplete and is retried (D88). Idempotency stores no 5xx (D47).

---

## 3. Failure modes

| failure | behaviour |
|---|---|
| A code path with no scope reads a store table | `RS001`, 500 `STORE_SCOPE_MISSING` (or a failed listener, retried), ERROR log with the origin, alert. Nothing is written. |
| A listener scoped to the event's store writes into another store | `42501`; the publication stays incomplete; `EVENT_PUBLICATIONS_FAILED` and `STORE_SCOPE_VIOLATION` alerts. Operator: fix, then the D88 resubmission completes it. |
| A false positive blocks sales in production | `RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT=PERMISSIVE` and a restart (D170); audited and alerted; `AccessPolicy` still decides. |
| `db-provision` fails (wrong superuser password, missing secret file) | the backend does not start (`service_completed_successfully`); the job's log names the step; nothing half-applied matters, every step is idempotent. |
| A logical restore without the provisioning step | the backend refuses to start (startup check: objects owned by the superuser, or the application role missing). README: run `restore.sh`, which provisions. |
| PITR to a point before a password rotation | `db-provision` re-sets the passwords from the current secrets at the next `up`. |
| The backend started with the superuser credentials by mistake | refused at startup (D163). |
| A new table without a decision | `RowLevelSecurityCoverageTest` fails in the slice that adds it. |
| A query loses its index under RLS (a non-leakproof predicate) | the slice's plan test fails; the query is rewritten (for example a `numeric` range into an `int8` cents column it already has, or a `store_id`-led prefix). |

---

## 4. Acceptance criteria (observable)

| # | criterion | spec |
|---|---|---|
| A69 | After `docker compose up` on a fresh and on an upgraded R5 installation: `restaurantpro_app` is not superuser, has no `BYPASSRLS` and owns nothing; `restaurantpro_owner` owns every application object; the backend container holds no `db_password`; starting the backend with the superuser, a `BYPASSRLS` role or an owner as the pool role → refused. The R5 image still starts on the migrated database (rollback). | §10, §11 |
| A70 | Fail closed: with no scope, any read or write of a protected table → `RS001` → 500 `STORE_SCOPE_MISSING`, counted and alerted; an explicit empty scope sees no store rows without an error. | §10 |
| A71 | Actor scope: a POS session of store A, through a test seam that skips `AccessPolicy`, reads no row of store B, cannot insert one (42501 → 500 `STORE_SCOPE_VIOLATION`) and updates none (0 rows); an account holding roles in A and B sees both; an installation-wide account sees all; a must-change-password account sees none. Through the API, a store-B resource addressed by id still answers **403** (D42 via `shared.store_of`), not 404. | §2, §10, §14 #9 |
| A72 | No store move: changing `store_id`, a transfer's `from_store_id`/`to_store_id`, or a child's parent key → `RS002`, for single-store, all-stores and system scopes; a child row pointing at another store's parent → 42501. | §8 |
| A73 | Pool reuse: with a pool of one connection, alternating scopes on the same backend pid never see each other's rows; a rollback does not revert a scope. | §10 |
| A74 | Actor-less contexts: every `@Scheduled` job, the bootstrap, the readiness hook, every listener (including a forced redelivery and a republication after restart), the print sender, the payment submitter, the rebuild runner and the `print_jobs` gauge under a token scrape run with the D166 scope, with no `RS001`; an unscoped `@Scheduled` method or a listener of an event without `storeId` fails the ArchUnit build. | §7, §12 |
| A75 | Coverage against the real migrations: 97 tables (after R5) protected with ENABLE + FORCE + the canonical policy + the RS002 trigger; every other table registered UNSCOPED with a reason; no `SECURITY DEFINER` outside the allow-list; dropping a policy in a scratch database makes the startup check refuse to start. | §8 |
| A76 | Cross-store transfer: a store-A-only shipper creates and ships to store B's warehouse; a store-B-only receiver sees and receives it; a store-C-only actor gets 403 on it; the A and B balances and movements are each in their own store. | §5, §8.2 |
| A77 | Performance: the R3 plan tests and every new plan test pass as the application role; the load smoke meets §12 (p95 < 500 ms for simple local queries and commands, 10 terminals). | §12 |
| A78 | The full suite (about 2 700 tests) is green with 0 skipped as the application role; `verify()` passes with no new edge. | §12 |
| A79 | Backups: `test-backup-restore.sh` restores into a fresh container, provisions, and the backend serves as the application role with RLS on; `test-pitr.sh --quick` keeps roles, ownership and policies; `test-two-installations.sh` shows that A's owner and application passwords do not open B and that B is byte-identical before and after A's restore. | §11, §14 #1, §14 #8 |

---

## 5. Process and what I need from you

The same gates as R1–R5. G1 is this document, G2/G3 are the [PRD](../prd/rls-store-isolation.md) slices S64–S70. Each slice is built with TDD in its own worktree (G4: `rtk mvn clean test` green, 0 skipped; S65: its drills' `--self-test`), reviewed by a fresh code reviewer (G5); S70 integrates; an architecture review (G6) follows; you merge (G7). **Every slice PR targets `main`; none is stacked**, and the run starts after R5's S63 has merged.

1. Approve the scope (§1) and D162–D173, or override by id. Check **D163**, **D165**, **D166** and **D168** in particular.
2. Answer P61–P68, or accept the recommendations. **P61, P62 and P64 shape the most work.**
3. Confirm that approving this also counts as G2/G3 for the PRD.
