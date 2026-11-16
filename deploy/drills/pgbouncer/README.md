# PgBouncer transaction-mode drill (R6 S75; A83, D178, D188)

**What it proves.** The backend's pool works behind PgBouncer in **transaction pooling** mode without mixing tenants:
`TenantBindingTransactionManager` binds `app.tenant_id`, `app.all_stores`, `app.store_ids` and `app.scope_origin`
with `set_config(…, true)` at every transaction begin, so the values end with the transaction and a pooled server
connection carries nothing to the next client.

**How.** `backend/src/test/java/com/xavier/backend/tenancy/PgBouncerTransactionModeTest.java` starts
`edoburu/pgbouncer:v1.24.1-p1` (Testcontainers) in front of the shared PostgreSQL 18.6 test container, with the
settings of [`pgbouncer.ini`](pgbouncer.ini) except `default_pool_size = 1` (one server connection for every client:
the worst case for a leak), and points the application context's pool at it (Flyway and the owner fixtures stay
direct; the test harness's session-level fixture write is switched off, `restaurantpro.test.fixture-checkouts=false`,
so the pool behaves as production's). Run: `deploy/drills/pgbouncer/run.sh`, or
`cd backend && rtk mvn test -Dtest=PgBouncerTransactionModeTest`.

| check | result (2026-11-16) |
| --- | --- |
| 40 transactions alternating tenants A and B (same store code in both) on one server backend: each sees only its own store | PASS |
| after a commit and after a rollback, the server connection carries no tenant outside a transaction, and an unbound transaction's protected read raises `RS003` | PASS |
| mutant: one session-level `set_config('app.tenant_id', …, false)` before a transaction | caught (the next client reads the leaked tenant) |
| `LISTEN` through the pooler, `NOTIFY` on PostgreSQL directly | the listener behind PgBouncer receives **nothing**; a direct listener receives it |

**Consequences for deployment.**
- `ChangeNotifier` (SSE fan-out, D60/D185) keeps one `LISTEN change_notice` connection per instance. In transaction
  mode the `LISTEN` runs on a server connection that returns to the pool, so notices are lost. That connection **must
  bypass the pooler**: set `restaurantpro.notify.listen-url` (`RESTAURANTPRO_NOTIFY_LISTEN_URL`, S71) to a direct JDBC
  URL; empty, it uses the pool's URL, which is right only without a pooler. `pg_notify` from a transaction through the pooler is fine (delivered on commit).
- Flyway (`restaurantpro_owner`, DDL, advisory locks) connects directly.
- Use PgBouncer ≥ 1.21 with `max_prepared_statements > 0` (pgjdbc's server-side prepared statements), or set
  `prepareThreshold=0` on the JDBC URL.
- RDS Proxy (the managed alternative, D188) is not drilled here: whether `set_config(…, true)` makes it pin the
  connection (correct, but less multiplexing) is to be checked with the B8 spike (`../b8-rds/README.md`).
