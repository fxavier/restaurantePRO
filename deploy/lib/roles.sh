# shellcheck shell=bash
# The database roles of ADR 0039 (D163), as the restore checks and the drills check them. Sourced, never run.

# Functions only the superuser console may run (EXECUTE revoked from PUBLIC by V1007, V1105, V3013/V3015; and the
# stand-in of test/sample.sql). A restore that drops privileges hands them back to PUBLIC, so to the pool.
RP_CONSOLE_FUNCTIONS="shared.end_account_sessions(uuid) audit.record_console_event(text,text,text,text) identity.mfa_break_glass_reset(text,text) shared.end_sessions_console()"

# $1 container, [$2 database]: one line per violation of D163, nothing when it holds. As the superuser, over the
# container's local socket.
roles_problems() {
  local functions="{\"${RP_CONSOLE_FUNCTIONS// /\",\"}\"}" # a text[] literal; each signature quoted (its commas)
  docker exec -i "$1" psql -X -q -At -v ON_ERROR_STOP=1 -v functions="$functions" -U restaurantpro -d "${2:-restaurantpro}" <<'SQL'
WITH r AS (SELECT * FROM pg_roles WHERE rolname IN ('restaurantpro_owner', 'restaurantpro_app'))
SELECT 'role ' || x || ' missing' FROM unnest(ARRAY['restaurantpro_owner', 'restaurantpro_app']) x
WHERE NOT EXISTS (SELECT 1 FROM r WHERE rolname = x)
UNION ALL
SELECT format('role %s: super=%s bypassrls=%s inherit=%s login=%s createrole=%s createdb=%s', rolname, rolsuper,
              rolbypassrls, rolinherit, rolcanlogin, rolcreaterole, rolcreatedb)
FROM r
WHERE rolsuper OR rolcreaterole OR rolcreatedb OR NOT rolcanlogin
   OR (rolname = 'restaurantpro_app' AND (rolbypassrls OR rolinherit))
   OR (rolname = 'restaurantpro_owner' AND NOT rolbypassrls);
SELECT to_regrole('restaurantpro_app') IS NOT NULL AND to_regrole('restaurantpro_owner') IS NOT NULL AS roles_exist
\gset
\if :roles_exist
CREATE TEMPORARY VIEW app_object AS
SELECT 'schema ' || n.nspname AS what, n.nspowner AS owner
FROM pg_namespace n
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'public') AND n.nspname NOT LIKE 'pg\_%'
UNION ALL
SELECT 'relation ' || c.oid::regclass, c.relowner
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid AND d.deptype = 'e')
UNION ALL
SELECT 'routine ' || p.oid::regprocedure, p.proowner
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e');
SELECT what || ' is owned by ' || pg_get_userbyid(owner) || ', not restaurantpro_owner'
FROM app_object WHERE owner <> 'restaurantpro_owner'::regrole ORDER BY what;
SELECT 'restaurantpro_app owns ' || count(*) || ' objects' FROM pg_shdepend
WHERE refobjid = 'restaurantpro_app'::regrole AND deptype = 'o' AND dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
HAVING count(*) > 0;
SELECT format('restaurantpro_app on %s: %s', c.oid::regclass, p)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace,
     LATERAL (SELECT 'has ' || x FROM unnest(ARRAY['TRUNCATE', 'REFERENCES', 'TRIGGER']) x
              WHERE has_table_privilege('restaurantpro_app', c.oid, x)
              UNION ALL
              SELECT 'cannot SELECT, INSERT, UPDATE and DELETE'
              WHERE c.oid IS DISTINCT FROM to_regclass('public.flyway_schema_history')
                AND NOT has_table_privilege('restaurantpro_app', c.oid, 'SELECT, INSERT, UPDATE, DELETE')) q(p)
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'
ORDER BY 1;
SELECT 'restaurantpro_app can ' || x || ' public.flyway_schema_history'
FROM unnest(ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE']) x
WHERE to_regclass('public.flyway_schema_history') IS NOT NULL
  AND has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', x);
SELECT 'restaurantpro_app can run the console function ' || f
FROM unnest(:'functions'::text[]) f
WHERE to_regprocedure(f) IS NOT NULL AND has_function_privilege('restaurantpro_app', to_regprocedure(f), 'EXECUTE');
SELECT format('%s has row-level security but %s', c.oid::regclass,
              CASE WHEN NOT c.relforcerowsecurity THEN 'not forced' ELSE 'no policy' END)
FROM pg_class c
WHERE c.relrowsecurity AND (NOT c.relforcerowsecurity OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid));
\endif
SQL
}

# $1 container, [$2 database]: "tables=<n> rls=<protected, enabled and forced> policies=<n>" (compare before and after).
rls_summary() {
  docker exec -i "$1" psql -X -q -At -v ON_ERROR_STOP=1 -U restaurantpro -d "${2:-restaurantpro}" -c "
    SELECT format('tables=%s rls=%s policies=%s',
      count(*) FILTER (WHERE true), count(*) FILTER (WHERE relrowsecurity AND relforcerowsecurity),
      (SELECT count(*) FROM pg_policy))
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg\_%'"
}

# $1 container, [$2 database]: fails the drill (fail(), defined by the caller) unless D163 holds; $3 describes when.
assert_roles() {
  local problems
  problems="$(roles_problems "$1" "${2:-restaurantpro}")" || fail "${3:-roles}: the role check could not run"
  [[ -z "$problems" ]] || fail "${3:-roles}: $(wc -l <<<"$problems" | tr -d ' ') violations of D163:
$(head -20 <<<"$problems")"
}

# $1 docker network (never "container:<name>": the image trusts loopback, so any password would do), $2 host, $3 role,
# $4 password file, [$5 sql, default SELECT 1]: prints the result as that role with that password, or psql's error.
role_login() {
  PGPASSWORD="$(cat "$4")" docker run --rm -e PGPASSWORD --network "$1" --entrypoint psql postgres:18.6-alpine \
    -X -At -v ON_ERROR_STOP=1 -h "$2" -U "$3" -d restaurantpro -c "${5:-SELECT 1}" 2>&1
}

# $1 directory: writes throwaway db_owner_password and db_app_password (0644 in a 0700 directory, as the drills'
# other secrets, so non-root container users can read them).
make_role_secrets() {
  openssl rand -hex 24 >"$1/db_owner_password"
  openssl rand -hex 24 >"$1/db_app_password"
  chmod 644 "$1/db_owner_password" "$1/db_app_password"
}
