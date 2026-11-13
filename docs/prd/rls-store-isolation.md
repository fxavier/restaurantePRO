# PRD RLS — Row-level security on store_id (backend, deploy)

Status: **PROPOSED** 2026-11-13, together with [rls-decisions.md](../design/rls-decisions.md) (cited as Dn, Pn, An). Earlier decisions: [backend-decisions.md](../design/backend-decisions.md), [r2-decisions.md](../design/r2-decisions.md), [r3-decisions.md](../design/r3-decisions.md), [r4-decisions.md](../design/r4-decisions.md), [r5-decisions.md](../design/r5-decisions.md). Glossary: [CONTEXT.md](../../CONTEXT.md). Spec: [product_description.md](../product_description.md). Conventions: [backend-conventions.md](../architecture/backend-conventions.md). ADR: [0039](../adr/0039-row-level-security-on-store-id.md).

**This run starts after R5's S63 has merged** (D172, P67). Base package `com.xavier.backend`, layout D12, Flyway versions D172. Every slice is built with TDD, **red first**: the named tests are written and fail before the code, and they are deterministic (injected `Clock`, fixed ids where order matters, no sleeps; concurrency tests use latches and real PostgreSQL). No `@Disabled`; `cd backend && rtk mvn clean test` green at exit with **0 skipped**, **as the application role** from S64 on.

- **Every slice PR targets `main`; none is stacked.** A slice branches from `main` once every slice it is blocked by has merged. Within a group, merge in ascending slice number, with the full suite after each merge.
- **Only S64** edits `pom.xml` (no new dependency is expected), `application.yaml`, `TestcontainersConfiguration`, `access/`, `config/persistence/**`, `shared/persistence/**`, `shared/web/GlobalExceptionHandler.java` and `deploy/postgres/provision-roles.sql`.
- **Only S65** edits `deploy/` (except `provision-roles.sql`), `backend/compose.yaml` and `backend/run-local.sh`.
- Slices do not commit `docs/architecture/modules/` (D44); only S70 does.
- The names below are binding. Internals are the implementer's call.

Frozen names:

| name | value |
|---|---|
| roles | `restaurantpro` (superuser, ops), `restaurantpro_owner` (Flyway, `BYPASSRLS`), `restaurantpro_app` (pool) |
| secrets | `db_password` (superuser; postgres and `db-provision` only), `db_owner_password`, `db_app_password` |
| properties / env | `spring.datasource.username` ← `RESTAURANTPRO_DB_USERNAME` (default `restaurantpro_app`); `spring.flyway.user` ← `RESTAURANTPRO_FLYWAY_USERNAME` (default `restaurantpro_owner`); `spring.flyway.password` ← `RESTAURANTPRO_FLYWAY_PASSWORD` (from `RESTAURANTPRO_FLYWAY_PASSWORD_FILE` in `backend-entrypoint.sh`); `restaurantpro.store-scope.enforcement` ← `RESTAURANTPRO_STORE_SCOPE_ENFORCEMENT` (`ENFORCE`) |
| settings | `app.all_stores`, `app.store_ids`, `app.scope_origin` (D164) |
| SQLSTATEs | `RS001` scope not set, `RS002` store change refused; `42501` from `WITH CHECK` |
| error codes | `STORE_SCOPE_MISSING`, `STORE_SCOPE_VIOLATION`, `STORE_CHANGE_REFUSED` (500) |
| metric / alerts | `store_scope_violations_total{kind}`; alerts `STORE_SCOPE_VIOLATION`, `STORE_SCOPE_PERMISSIVE` |

---

## S64 — Platform prefactor (runs alone, first)

Switches every test and the backend to the application role and adds the scope plumbing. **No policy yet**, so behaviour is unchanged except who connects.

**Write scope:**
- `access/StoreScope.java`, `access/SystemScope.java`, `access/SystemScoped.java`, `access/StoreScopes.java`, `access/StoreScopeFallback.java`
- new `config/persistence/**` (`StoreScopedDataSource`, `StoreScopeDataSourcePostProcessor`, `StoreScopeAdvisor`, `StoreScopeStartupCheck`, `StoreScopeEnforcement`), new `config/alerts/StoreScopeAlertSource.java`
- new `shared/persistence/StoreScopeViolations.java`, `shared/persistence/StoreProbe.java`; `shared/web/GlobalExceptionHandler.java`
- `@SystemScoped` on the 14 `@Scheduled` methods, `CompanyBootstrapRunner.run`, `RebuildService.abandonInterruptedRuns`, `AdminBootstrapService`'s listener; `StoreScopes.propagate` in `PrintWorker`, `PaymentSubmitter`, `RebuildService`; `METRICS` in `ProductionMetrics`; `ALERTS` around the R5 alert aggregation in `config/alerts`
- migrations `shared/V1008`, `organization/V2004`, `identity/V3015`, `customers/V11004`
- `application.yaml`; `deploy/postgres/provision-roles.sql`
- tests: `TestcontainersConfiguration`, new `OwnerDatabase`, `StoreScopeTestSupport`, the fixture-thread extension (+ `src/test/resources/junit-platform.properties`), `@StrictStoreScope`; the ~30 test files with privileged SQL move to `OwnerDatabase`; `src/test/.../architecture`
- `docs/architecture/backend-conventions.md` (rule rows)

| contract | detail |
|---|---|
| `access.StoreScope` | `record StoreScope(boolean allStores, Set<UUID> stores, String origin)`; `static StoreScope of(Actor)` (D164 table); `UNSET`, `none(String origin)`, `all(String origin)`; `String storeIdsLiteral()` (sorted, lower case, `{}`) |
| `access.SystemScope` | enum with `Reach {ALL, NONE}` and a `reason`: `BOOTSTRAP, PRINT_WORKER, PAYMENT_RECOVERY, RESERVATIONS, STOCK_RECONCILE, REPORTING_RECOVERY, METRICS, ALERTS` (ALL); `EVENT_MAINTENANCE, IDENTITY_MAINTENANCE, HOUSEKEEPING` (NONE) |
| `access.SystemScoped` | `@Target(METHOD) @Retention(RUNTIME) @interface SystemScoped { SystemScope value(); }` |
| `access.StoreScopes` (`@Component`) | `StoreScope current()`; `void run(SystemScope, Runnable)`, `<T> T call(SystemScope, Supplier<T>)`; `<T> T callForStore(UUID storeId, String origin, Supplier<T>)` (listeners); `Runnable propagate(Runnable)`. `run`/`call*` throw `IllegalStateException` when a transaction is active. |
| `access.StoreScopeFallback` | `StoreScope whenNoActor()`; production bean returns `UNSET` |
| `config.persistence.StoreScopedDataSource` | D165: `set_config` ×3 on every `getConnection()`, evict on failure, refuse `autoCommit=false`, `PERMISSIVE` → all stores |
| `config.persistence.StoreScopeAdvisor` | infrastructure-role advisor, `HIGHEST_PRECEDENCE + 10`: `@SystemScoped` methods; `@ApplicationModuleListener` methods → event's `storeId()` record component; classifies and counts violations |
| `config.persistence.StoreScopeStartupCheck` | D163 checks after Flyway (`@DependsOn("flywayInitializer")`) |
| `shared.persistence.StoreScopeViolations` | `Optional<Kind> classify(Throwable)` (`SCOPE_MISSING`, `WRITE_REFUSED`, `STORE_CHANGE`) by SQL state; `void record(Kind, String origin)` (counter, ERROR log, 24 h memory for the alert) |
| `shared.persistence.StoreProbe` | `Optional<UUID> storeOf(String table, UUID id)` → `SELECT shared.store_of(CAST(:t AS regclass), :id)` |
| `V1008__shared_row_level_security.sql` | `rls_all_stores()`, `rls_store_ids()` (plpgsql, `STABLE PARALLEL SAFE`, raise `RS001`); `rls_reject_scope_change()` (`RS002`); `rls_protect_store_table`, `rls_protect_store_or_all_table`, `rls_protect_child_table`, `rls_register_unscoped`; table `rls_unscoped_table`; `store_of(regclass, uuid)` `SECURITY DEFINER SET search_path = pg_catalog`; registers `event_publication`, `event_publication_archive`, `flyway_schema_history`, `shared.idempotency_record`, `shared.spring_session`, `shared.spring_session_attributes` |
| `V2004`, `V3015`, `V11004` | `rls_register_unscoped` for organization (3), identity (9 + R5's 4; revoke `EXECUTE` on `identity.mfa_break_glass_reset` from `PUBLIC`), customers (8), each with its D167 reason |
| `provision-roles.sql` | D163 steps; psql variables `:'owner_password'`, `:'app_password'`; idempotent; never echoes a password |
| Architecture rules | `scheduledEntryPointsAreSystemScoped`, `listenersHaveAStoreOrASystemScope`, `executorSubmissionsPropagateTheStoreScope`, `onlyConfigUsesHikari`; each with an `ArchitectureRulesFireTest` fixture |

**Tests (red first):** `StoreScopeTest`, `StoreScopedDataSourceTest`, `StoreScopePoolReuseTest` (on a scratch table protected by `rls_protect_store_table` inside the test), `StoreScopeConcurrencyTest`, `StoreScopeStartupCheckTest`, `SystemScopeRegistryTest`, `ActorlessStoreScopeTest` (`@StrictStoreScope`; asserts every entry point resolves its expected scope, through a probe of `current_setting`), `StoreProbeTest`, `RowLevelSecurityCoverageTest` (module-scoped helper `assertModuleClassified(String schema)`; S64 asserts shared, public, organization, identity, customers), `CodedErrorContractTest` (three codes), `ArchitectureRulesFireTest`, `ModularityTests`. The full suite is green as `restaurantpro_app`.

**Acceptance:** A69 (backend part), A70, A73, A74 (scope resolution), A78.

---

## Group 1 (after S64, in parallel): S65 ∥ S66 ∥ S67 ∥ S68 ∥ S69

Every policy slice: one migration per module calling the V1008 helpers (no hand-written policy text); its module's D42 probe methods switched to `StoreProbe`; its `<Module>RowLevelSecurityTest`; `assertModuleClassified` for its schemas; plan tests for its hot queries (D171) after an audit of `numeric`/`jsonb`/`LIKE` predicates on protected tables (D169); fixes to its own tests. Each adds its listeners and jobs to `ActorlessStoreScopeTest` cases **in a new test class of its own module** (`<Module>ActorlessScopeTest`), so the S64 class is not edited.

### S65 — Deploy: roles, provisioning, restore, drills
**Write scope:** `deploy/compose.yaml`, `deploy/postgres/provision.sh` (new), `deploy/backend-entrypoint.sh`, `deploy/restore.sh`, `deploy/verify-restore.sh`, `deploy/test-backup-restore.sh`, `deploy/test-pitr.sh`, `deploy/test-two-installations.sh`, `deploy/drills/isolation/**`, `deploy/README.md`, `deploy/.env.example` (comment only), `backend/compose.yaml`, `backend/run-local.sh`.
- Compose: secrets `db_owner_password`, `db_app_password`; one-shot `db-provision` (pinned postgres image, `restart: "no"`, internal network, secrets `db_password`, `db_owner_password`, `db_app_password`, mounts `provision.sh` and `provision-roles.sql` read-only); `backend` drops `db_password`, gets the two new secrets and the frozen env, and `depends_on: db-provision: condition: service_completed_successfully`.
- `provision.sh`: `PGPASSWORD` from the superuser file, `\getenv` for the two passwords, `SET log_statement = 'none'`, `\i provision-roles.sql`; `--self-test` against a throwaway container (twice: the second run changes nothing).
- `restore.sh`: `pg_restore --no-owner --no-privileges`, then provisioning; `verify-restore.sh` the same.
- README: "Database roles" (what each is for), first install and upgrade steps (create two secrets, `up`), the `PERMISSIVE` runbook (D170), rollback to the R5 image.
**Tests:** `provision.sh --self-test`; `test-backup-restore.sh`, `test-pitr.sh --quick`, `test-two-installations.sh` extended per D171. **Acceptance:** A69 (deploy part), A79.

### S66 — Orders, dining, production
**Write scope:** `orders/`, `dining/`, `production/` (probe methods, tests); `db/migration/orders/V6030`, `db/migration/dining/V4007`, `db/migration/production/V7010`.
**Migrations:** orders: 5 STORE, 7 CHILD (D167 table; `delivery_event` → `orders.delivery (order_id)`); dining: 6 STORE; production: 5 STORE.
**Tests:** `OrdersRowLevelSecurityTest`, `DiningRowLevelSecurityTest`, `ProductionRowLevelSecurityTest`; plan tests: open orders by table and status, lines of one order, print jobs due; `ProductionActorlessScopeTest` (production listeners, print sender), `DiningActorlessScopeTest` (no-show job). **Acceptance:** A71, A72 (their tables).

### S67 — Settlement, cash, fiscal
**Write scope:** `settlement/`, `cash/`, `fiscal/` (probe methods, tests); `db/migration/settlement/V9060`, `db/migration/cash/V8011`, `db/migration/fiscal/V10012`.
**Migrations:** settlement: 8 STORE, 4 CHILD, register `payment_method`, `order_settlement`; cash: 6 STORE, 2 CHILD; fiscal: 2 STORE, 4 CHILD.
**Tests:** `SettlementRowLevelSecurityTest`, `CashRowLevelSecurityTest`, `FiscalRowLevelSecurityTest` (including that the existing triggers `check_refund_within_payment`, `check_payment_allocation_sum`, `check_payment_allocated` and the fiscal guards work under a store scope); plan tests: payments of an order, the open cash session, the fiscal series `FOR UPDATE`; `SettlementActorlessScopeTest` (sweepers, resolver, gauge, submitter). The R3 lock-order and concurrency ITs stay green unchanged. **Acceptance:** A71, A72, §14 #9 under RLS (fiscal, closings).

### S68 — Inventory
**Write scope:** `inventory/` (including the `stock_balance` writer, entity and rebuild); `db/migration/inventory/V13080`, `V13081`.
**Migrations:** `V13080`: `warehouse_id_store_uk UNIQUE (id, store_id)`; `stock_balance.store_id UUID` backfilled from `warehouse`, then `NOT NULL` and FK `(warehouse_id, store_id) → warehouse (id, store_id)`. `V13081`: 15 STORE (`warehouse` with open reads, D168), `stock_balance` STORE, `stock_transfer` SPECIAL, 7 CHILD, 10 registered; `inventory.stock_transfer_stores(uuid)` `SECURITY DEFINER`.
**Tests:** `InventoryRowLevelSecurityTest`; `InterStoreTransferUnderRlsTest` (A76); plan tests: `stock_balance` by warehouse, valuation by store, movements by store and day; `InventoryActorlessScopeTest` (consumption listeners, reconcile job). The D132 deadlock ITs stay green. **Acceptance:** A71, A72, A76.

### S69 — Catalog, promotions, reporting, audit
**Write scope:** `catalog/`, `promotions/`, `reporting/`, `audit/` (probe methods, tests); `db/migration/catalog/V5007`, `promotions/V12005`, `reporting/V14006`, `audit/V1106`.
**Migrations:** catalog: 5 STORE (with R5's `store_item_exclusion`), 2 STORE_OR_ALL, 2 CHILD, 11 registered; promotions: 2 STORE, 10 registered; reporting: 12 STORE; audit: `audit_event` SPECIAL, revoke `EXECUTE` on `audit.record_console_event` from `PUBLIC`.
**Tests:** `CatalogRowLevelSecurityTest` (a store-A manager cannot change store B's prices even through a seam: §14 #9), `PromotionsRowLevelSecurityTest`, `ReportingRowLevelSecurityTest`, `AuditRowLevelSecurityTest` (login audited with no actor; store-less rows only for all stores); `ReportPlanTest`, `LoyaltyBalancePlanTest` as the application role; plan tests for the release of a store and audit by store and action; `ReportingActorlessScopeTest` (projection and cost-of-goods listeners, rebuild runner). **Acceptance:** A71, A72, A77 (plans).

---

## S70 — Integrate (alone, after every slice has merged)

**Write scope:** `src/test/.../e2e`; `RowLevelSecurityCoverageTest` (the all-schemas assertion); `docs/architecture/modules/`; `CONTEXT.md` (store scope, "âmbito de loja"; system scope, "âmbito de sistema"); `docs/architecture/backend-conventions.md`; `docs/adr/0039` (*Proposed* → *Accepted*), `docs/adr/README.md`; `docs/qa/acceptance-matrix.md`; `deploy/load/**` (run as the application role).

**Deliverables:**
- **End-to-end** over HTTP with real security, two stores A and B: a POS of A, a manager holding A only, a manager holding A and B, an installation-wide admin; orders, payment, document, cash close and a report in A; each actor sees exactly its stores; another store's resource by id → 403; a cross-store stock transfer A → B (A76); a listener chain (round → production → consumption → reporting) completes under the event's store scope; `PERMISSIVE` mode starts, alerts and audits.
- Coverage strict over every schema (A75); the full suite (A78); the load smoke (A77); the three deploy drills run once on the integrated tree (A79).
- **Acceptance:** A69–A79 end to end.

---

## Blocked-by graph and parallel groups

```
R5 S63 merged
 └─► S64 Platform prefactor (alone)
      ├─► S65 Deploy roles and drills ───────────────┐
      ├─► S66 Orders, dining, production ────────────┤
      ├─► S67 Settlement, cash, fiscal ──────────────┤
      ├─► S68 Inventory ─────────────────────────────┤
      └─► S69 Catalog, promotions, reporting, audit ─┴─► S70 Integrate
```

| group | slices (parallel) | why the write sets are disjoint |
|---|---|---|
| 0 | S64 alone | `access/`, `config/persistence/**`, `shared/persistence/**`, the exception handler, annotations at the entry points, four registration migrations, `application.yaml`, the test harness, `provision-roles.sql` |
| 1 | S65 ∥ S66 ∥ S67 ∥ S68 ∥ S69 | `deploy/` and the dev compose; `orders/`+`dining/`+`production/`+V6030/V4007/V7010; `settlement/`+`cash/`+`fiscal/`+V9060/V8011/V10012; `inventory/`+V13080/V13081; `catalog/`+`promotions/`+`reporting/`+`audit/`+V5007/V12005/V14006/V1106. Each adds tests only in its own module packages. |
| 2 | S70 alone | e2e tests, coverage strict mode, docs, ADR status, load plan |

Rules: only S64 edits the shared infrastructure listed above; only S65 edits `deploy/`; slices code against the frozen names and never read another module's tables; the R5 rules stay in force.

## Acceptance criteria → slices

| criterion (rls-decisions §4) | slices |
|---|---|
| A69 | S64, S65, S70 |
| A70, A73 | S64, S70 |
| A71, A72 | S66, S67, S68, S69, S70 |
| A74 | S64, S66–S69, S70 |
| A75 | S64, S66–S69 (per module), S70 (all schemas) |
| A76 | S68, S70 |
| A77 | S66–S69 (plans), S70 (load) |
| A78 | every slice |
| A79 | S65, S70 |
