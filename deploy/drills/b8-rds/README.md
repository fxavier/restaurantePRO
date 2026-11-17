# B8 spike: can managed PostgreSQL keep D188 (a)? (R6 S75, open item O2)

**Status: not run.** These steps are for the user (or operations) to run on a throw-away AWS account. The agent
creates no AWS resource. Record the outcome in `docs/design/multitenancy-decisions.md` (D188, O2).

**Question.** restaurantPRO's roles (D163, D188) need a non-superuser **`restaurantpro_owner` with `BYPASSRLS`**
that owns every object and runs Flyway, plus tables with `FORCE ROW LEVEL SECURITY`. On Amazon RDS for PostgreSQL 18
and, if 18 is offered, Aurora PostgreSQL:

- **(a) kept** if the master user (`rds_superuser`, not a real superuser) can give a role `BYPASSRLS`;
- **(b) fallback** otherwise: the owner has no `BYPASSRLS`, the tables keep `FORCE`, data migrations and the tenant
  export/restore bind the tenant explicitly. `deploy/test-tenant-export.sh` and `deploy/test-tenant-restore.sh`
  already prove (b) works for the export and the restore.

**Cost.** One `db.t4g.micro` (or the smallest Aurora Serverless v2 capacity) for under an hour. No Multi-AZ, no
backups, public access off. Reach it from a bastion or CloudShell in the same VPC.

## Steps

Create the instance (console or CLI), engine PostgreSQL **18.x**, a throw-away master user `rp_master`. Then, with
`psql` as `rp_master`:

1. **The owner role**
   ```sql
   CREATE ROLE r6_owner LOGIN PASSWORD '<throw-away>' BYPASSRLS;   -- record the exact error if refused
   ALTER ROLE r6_owner BYPASSRLS;                                   -- the same, on an existing role
   SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname IN ('rp_master', 'r6_owner');
   ```
   If refused, also try `GRANT rds_superuser TO r6_owner` and check whether RLS still applies to it: the backend must
   never hold a role that bypasses RLS, so `rds_superuser` membership is **not** an acceptable owner either way.
2. **FORCE and a restrictive policy as a plain owner**
   ```sql
   CREATE DATABASE r6 OWNER r6_owner;
   \c r6 r6_owner
   CREATE TABLE t (tenant_id uuid NOT NULL, v int);
   ALTER TABLE t ENABLE ROW LEVEL SECURITY, FORCE ROW LEVEL SECURITY;
   CREATE POLICY tenant_scope ON t AS RESTRICTIVE FOR ALL
       USING (tenant_id = current_setting('app.tenant_id')::uuid)
       WITH CHECK (tenant_id = current_setting('app.tenant_id')::uuid);
   CREATE POLICY open_all ON t AS PERMISSIVE FOR ALL USING (true) WITH CHECK (true);
   ```
3. **Provisioning and the real migrations as `r6_owner`.** From a checkout of this repository on the bastion:
   - adapt and run `deploy/postgres/provision-roles.sql` as `rp_master` (it creates `restaurantpro_owner` and
     `restaurantpro_app`; on RDS the `ALTER ROLE … BYPASSRLS` line is the one under test);
   - run Flyway with the backend's settings as the owner, e.g. with the `flyway/flyway:12-alpine` image:
     `-url=jdbc:postgresql://<endpoint>:5432/r6 -user=restaurantpro_owner -locations=filesystem:backend/src/main/resources/db/migration -outOfOrder=true migrate`;
   - note any migration that fails (V1007's console functions, V1008–V1010's helpers, V15001/V15002's
     `SECURITY DEFINER` functions).
4. **The coverage queries.** Run the catalogue checks of
   `backend/src/test/java/com/xavier/backend/architecture/RowLevelSecurityCoverageTest.java` (`pg_policies`,
   `pg_class.relforcerowsecurity`, `pg_proc.prosecdef`/`proconfig`, `has_table_privilege` for `restaurantpro_app`)
   against the migrated database, and compare with a Testcontainers run.
5. **Behaviour as the application role.** Connect as `restaurantpro_app`: an unbound read of a tenant table raises
   `RS003`; `SELECT set_config('app.tenant_id', '<A>', true)` in a transaction shows tenant A's rows only.
6. **RDS Proxy (optional, D188 hosting).** Put an RDS Proxy in front for `restaurantpro_app` and repeat step 5 in
   transaction pooling; note whether `set_config(…, true)` makes the proxy pin the connection (in
   `DatabaseConnectionsCurrentlySessionPinned`). The PgBouncer equivalent is drilled in `../pgbouncer/README.md`.

Delete the instance (and the proxy) when done.

## Record

| step | RDS PostgreSQL 18 | Aurora PostgreSQL (18 if offered) |
| --- | --- | --- |
| 1 `CREATE/ALTER ROLE … BYPASSRLS` by the master user | | |
| 2 `FORCE` + restrictive policy by a plain owner | | |
| 3 provisioning + Flyway V1–V15002 as the owner | | |
| 4 coverage queries equal to Testcontainers | | |
| 5 `RS003` unbound / tenant A only when bound | | |
| 6 RDS Proxy pinning on `set_config(…, true)` | | |

**Decision:** (a) if step 1 succeeds on the chosen engine, otherwise (b), recorded in D188 and O2. If neither (a)
nor (b) holds (for instance `FORCE` refused to a plain owner), stop and ask (PRD R6 "Stop and ask").
