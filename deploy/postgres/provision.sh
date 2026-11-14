#!/usr/bin/env bash
# Database roles of an installation (ADR 0039, D163): runs provision-roles.sql (next to this script) as the
# superuser, so the backend's pool is restaurantpro_app and its Flyway restaurantpro_owner. Idempotent: run at
# every `docker compose up` (service db-provision), by restore.sh and verify-restore.sh, and in development.
#
# Usage: provision.sh [--secrets DIR]
#            in the db-provision service of compose.yaml: connects to PGHOST (default postgres) as the superuser
#            with the password in DIR/db_password (default /run/secrets)
#        provision.sh --container NAME [--db NAME] [--secrets DIR]
#            from the host, through docker exec into a PostgreSQL container, as the superuser over its local
#            socket (no superuser password needed); DIR defaults to deploy/secrets
#        provision.sh --self-test
#            against a throwaway container: an R5-shaped database (objects owned by the superuser, an extension, the
#            Flyway history, a policy) is provisioned twice through the db-provision path and once through
#            --container; the second and third runs change nothing; also a missing secret, a wrong superuser
#            password and a password rotation. Needs only Docker; leaves nothing behind.
# The role passwords are DIR/db_owner_password and DIR/db_app_password (openssl rand -hex 24; a trailing newline
# is ignored, as the backend entrypoint does). They reach psql through its environment and \getenv, never a command
# line, and are never printed; statement logging is off for the session. Exits non-zero on any failure, naming the
# step; a failed run leaves nothing half-applied that matters (every step is idempotent).
set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL="$SCRIPT_DIR/provision-roles.sql"
DB_USER=restaurantpro
db=restaurantpro
container=""
secrets=""
self_test=false

die() { echo "provision: $*" >&2; exit 1; }
log() { echo "provision: $*" >&2; }

while (($#)); do
  case "$1" in
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --secrets) secrets="${2:?--secrets needs a value}"; shift 2 ;;
    --self-test) self_test=true; shift ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

secret() { # $1 file: its content without the trailing newline; refuses a missing or empty file
  [[ -f "$1" ]] || die "secret file not found: $1"
  local value
  value="$(cat "$1")"
  [[ -n "$value" ]] || die "secret file is empty: $1"
  printf '%s' "$value"
}

# The script psql reads on stdin: the two passwords from its environment, logging off, then the provisioning file.
script() {
  printf '%s\n' '\set ON_ERROR_STOP on' '\getenv owner_password RP_OWNER_PASSWORD' '\getenv app_password RP_APP_PASSWORD' \
    "SET log_statement = 'none';" "SET log_min_error_statement = 'panic';"
  cat "$SQL"
}

provision() {
  [[ -f "$SQL" ]] || die "provision-roles.sql not found next to $0"
  local owner_pw app_pw
  if [[ -n "$container" ]]; then
    secrets="${secrets:-$SCRIPT_DIR/../secrets}"
    owner_pw="$(secret "$secrets/db_owner_password")"
    app_pw="$(secret "$secrets/db_app_password")"
    log "provisioning the database roles in database $db of container $container"
    script | RP_OWNER_PASSWORD="$owner_pw" RP_APP_PASSWORD="$app_pw" \
      docker exec -i -e RP_OWNER_PASSWORD -e RP_APP_PASSWORD "$container" \
      psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$db" -f - \
      || die "provision-roles.sql failed in database $db of container $container (see above)"
  else
    secrets="${secrets:-/run/secrets}"
    local super_pw
    super_pw="$(secret "$secrets/db_password")"
    owner_pw="$(secret "$secrets/db_owner_password")"
    app_pw="$(secret "$secrets/db_app_password")"
    log "provisioning the database roles in database $db on ${PGHOST:-postgres}"
    script | PGPASSWORD="$super_pw" RP_OWNER_PASSWORD="$owner_pw" RP_APP_PASSWORD="$app_pw" \
      psql -X -q -v ON_ERROR_STOP=1 -h "${PGHOST:-postgres}" -U "$DB_USER" -d "$db" -f - \
      || die "provision-roles.sql failed in database $db on ${PGHOST:-postgres} (see above)"
  fi
  log "done: restaurantpro_owner owns the application objects, restaurantpro_app has data privileges only"
}

# --- self-test ----------------------------------------------------------------------------------------------------
self_test() {
  local image="postgres:18.6-alpine" # D4: the pin of compose.yaml's db-provision and the backend tests
  local run_id
  run_id="rp-provision-selftest-$$-$(date +%s)"
  local pg="$run_id-pg" net="$run_id-net" work
  work="$(mktemp -d "${TMPDIR:-/tmp}/rp-provision-selftest.XXXXXX")"
  # shellcheck disable=SC2064 # expand now: the names are fixed for this run
  trap "docker rm -f -v '$pg' >/dev/null 2>&1 || true; docker network rm '$net' >/dev/null 2>&1 || true; rm -rf '$work'" EXIT
  fail() { echo "FAIL: provision self-test: $*" >&2; exit 1; }
  ok() { echo "ok: $*" >&2; }
  q() { docker exec -i "$pg" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$db" "$@"; }
  login() { # $1 role, $2 password file, $3 sql: as that role, from another container (loopback is trusted in the image)
    PGPASSWORD="$(cat "$2")" docker run --rm --network "$net" -e PGPASSWORD --entrypoint psql "$image" \
      -X -q -At -v ON_ERROR_STOP=1 -h "$pg" -U "$1" -d "$db" -c "$3" 2>&1
  }
  # As db-provision runs it: the pinned image, the internal network, the script and file read-only, the secrets.
  as_service() { # $1 secrets directory
    docker run --rm --network "$net" -e PGHOST="$pg" \
      -v "$SCRIPT_DIR/provision.sh:/etc/restaurantpro/provision.sh:ro" \
      -v "$SQL:/etc/restaurantpro/provision-roles.sql:ro" -v "$1:/run/secrets:ro" \
      --entrypoint bash "$image" /etc/restaurantpro/provision.sh
  }
  # Catalog state that provisioning decides (role attributes, owners, ACLs, default ACLs, RLS, policies); password
  # hashes are re-salted at every run, so logins are checked instead.
  state() {
    q <<'SQL'
SELECT 'role', rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolcanlogin, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname LIKE 'restaurantpro%' ORDER BY rolname;
SELECT 'database', datname, pg_get_userbyid(datdba), datacl FROM pg_database WHERE datname = current_database();
SELECT 'schema', nspname, pg_get_userbyid(nspowner), nspacl FROM pg_namespace
WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' ORDER BY nspname;
SELECT 'relation', c.oid::regclass::text, c.relkind, pg_get_userbyid(c.relowner), c.relacl, c.relrowsecurity,
       c.relforcerowsecurity
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f') AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
ORDER BY 2;
SELECT 'routine', p.oid::regprocedure::text, pg_get_userbyid(p.proowner), p.proacl
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema' ORDER BY 2;
SELECT 'type', t.oid::regtype::text, pg_get_userbyid(t.typowner) FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
WHERE t.typtype IN ('e', 'd') AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema' ORDER BY 2;
SELECT 'default acl', pg_get_userbyid(defaclrole), defaclnamespace, defaclobjtype, defaclacl FROM pg_default_acl ORDER BY 2, 3, 4;
SELECT 'policy', schemaname, tablename, policyname, roles, qual FROM pg_policies ORDER BY 2, 3, 4;
SQL
  }
  secrets_in() { # $1 directory: throwaway superuser and role passwords
    mkdir -p "$1"
    cp "$work/db_password" "$1/db_password"
    openssl rand -hex 24 >"$1/db_owner_password"
    openssl rand -hex 24 >"$1/db_app_password"
    chmod 755 "$1"
    chmod 644 "$1"/*
  }
  no_secret_in() { # $1 file of output: none of the passwords of directory $2 appears in it
    local f
    for f in "$2"/*; do
      if grep -qF "$(cat "$f")" "$1"; then fail "a password from $(basename "$f") appears in $(basename "$1")"; fi
    done
  }

  echo "--- starting throwaway PostgreSQL $pg ($image)" >&2
  docker network create "$net" >/dev/null
  openssl rand -hex 16 | tr -d '\n' >"$work/db_password"
  POSTGRES_PASSWORD="$(cat "$work/db_password")" docker run -d --name "$pg" --network "$net" -e POSTGRES_PASSWORD \
    -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$db" "$image" >/dev/null
  local ready=false
  for _ in $(seq 1 60); do
    if docker exec "$pg" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$db" 2>/dev/null; then ready=true; break; fi
    sleep 1
  done
  $ready || fail "$pg did not become ready"
  secrets_in "$work/s1"

  echo "--- an R5-shaped database: every object owned by the superuser" >&2
  q <<'SQL'
CREATE EXTENSION pg_trgm;
CREATE SCHEMA module;
CREATE TYPE module.status AS ENUM ('OPEN', 'CLOSED');
CREATE DOMAIN module.code AS text CHECK (VALUE <> '');
CREATE TABLE module.thing (id bigserial PRIMARY KEY, store_id uuid NOT NULL, code module.code, status module.status);
CREATE SEQUENCE module.free_counter;
CREATE VIEW module.thing_view AS SELECT id, code FROM module.thing;
CREATE FUNCTION module.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
CREATE TRIGGER thing_touch BEFORE UPDATE ON module.thing FOR EACH ROW EXECUTE FUNCTION module.touch();
CREATE FUNCTION module.console_only() RETURNS int LANGUAGE sql AS 'SELECT 1';
REVOKE EXECUTE ON FUNCTION module.console_only() FROM PUBLIC;
ALTER TABLE module.thing ENABLE ROW LEVEL SECURITY;
ALTER TABLE module.thing FORCE ROW LEVEL SECURITY;
CREATE POLICY store_scope ON module.thing USING (current_setting('app.all_stores', true) = 'on');
INSERT INTO module.thing (store_id, code, status) VALUES (gen_random_uuid(), 'a', 'OPEN');
CREATE TABLE public.flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), success boolean NOT NULL);
INSERT INTO public.flyway_schema_history VALUES (1, '1', true);
SQL

  echo "--- refusals: a missing secret, a wrong superuser password" >&2
  mkdir -p "$work/missing"
  cp "$work/s1/db_password" "$work/s1/db_owner_password" "$work/missing/"
  chmod 755 "$work/missing"
  chmod 644 "$work/missing"/*
  if as_service "$work/missing" >"$work/missing.out" 2>&1; then fail "a run without db_app_password succeeded"; fi
  grep -q 'secret file not found: /run/secrets/db_app_password' "$work/missing.out" || fail "missing secret: $(cat "$work/missing.out")"
  [[ "$(q -c "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'restaurantpro\_%'")" == 0 ]] || fail "a refused run created roles"
  secrets_in "$work/wrong"
  openssl rand -hex 16 >"$work/wrong/db_password"
  if as_service "$work/wrong" >"$work/wrong.out" 2>&1; then fail "a run with a wrong superuser password succeeded"; fi
  grep -q 'password authentication failed' "$work/wrong.out" || fail "wrong superuser password: $(cat "$work/wrong.out")"
  no_secret_in "$work/wrong.out" "$work/wrong"
  [[ "$(q -c "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'restaurantpro\_%'")" == 0 ]] || fail "a refused run created roles"
  ok "a missing secret and a wrong superuser password are refused, name the cause, print no password and create nothing"

  echo "--- run 1, as the db-provision service" >&2
  as_service "$work/s1" >"$work/run1.out" 2>&1 || { cat "$work/run1.out" >&2; fail "the first run failed"; }
  no_secret_in "$work/run1.out" "$work/s1"
  local first
  first="$(state)"
  local roles
  roles="$(q -c "SELECT string_agg(format('%s super=%s bypassrls=%s inherit=%s login=%s', rolname, rolsuper, rolbypassrls, rolinherit, rolcanlogin), '; ' ORDER BY rolname)
                 FROM pg_roles WHERE rolname IN ('restaurantpro_owner', 'restaurantpro_app')")"
  [[ "$roles" == "restaurantpro_app super=f bypassrls=f inherit=f login=t; restaurantpro_owner super=f bypassrls=t inherit=t login=t" ]] \
    || fail "role attributes: $roles"
  local owned
  owned="$(q <<'SQL'
SELECT count(*) FROM (
  SELECT relowner AS owner FROM pg_class WHERE relnamespace IN (SELECT oid FROM pg_namespace WHERE nspname IN ('module', 'public'))
    AND relkind IN ('r', 'v', 'S') AND oid NOT IN (SELECT objid FROM pg_depend WHERE deptype = 'e')
  UNION ALL SELECT nspowner FROM pg_namespace WHERE nspname = 'module'
  UNION ALL SELECT proowner FROM pg_proc WHERE pronamespace = 'module'::regnamespace
  UNION ALL SELECT typowner FROM pg_type WHERE typnamespace = 'module'::regnamespace AND typtype IN ('e', 'd')) o
WHERE owner <> 'restaurantpro_owner'::regrole
SQL
)"
  [[ "$owned" == 0 ]] || fail "$owned application objects are not owned by restaurantpro_owner"
  [[ "$(q -c "SELECT count(*) FROM pg_shdepend WHERE refobjid = 'restaurantpro_app'::regrole AND deptype = 'o'")" == 0 ]] \
    || fail "restaurantpro_app owns objects"
  [[ "$(q -c "SELECT pg_get_userbyid(proowner) FROM pg_proc WHERE proname = 'similarity'")" == "$DB_USER" ]] \
    || fail "an extension's function changed owner"
  local privileges
  privileges="$(q <<'SQL'
SELECT concat_ws(' ',
  has_table_privilege('restaurantpro_app', 'module.thing', 'SELECT, INSERT, UPDATE, DELETE'),
  has_table_privilege('restaurantpro_app', 'module.thing', 'TRUNCATE'),
  has_table_privilege('restaurantpro_app', 'module.thing', 'REFERENCES'),
  has_table_privilege('restaurantpro_app', 'module.thing', 'TRIGGER'),
  has_sequence_privilege('restaurantpro_app', 'module.free_counter', 'USAGE, SELECT'),
  has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', 'SELECT'),
  has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', 'INSERT'),
  has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', 'UPDATE'),
  has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', 'DELETE'),
  has_table_privilege('restaurantpro_app', 'public.flyway_schema_history', 'TRUNCATE'),
  has_function_privilege('restaurantpro_app', 'module.console_only()', 'EXECUTE'),
  has_schema_privilege('restaurantpro_app', 'module', 'CREATE'))
SQL
)"
  [[ "$privileges" == "t f f f t t f f f f f f" ]] \
    || fail "restaurantpro_app privileges (DML, TRUNCATE, REFERENCES, TRIGGER, sequence, history SELECT/INSERT/UPDATE/DELETE/TRUNCATE, console function, CREATE): $privileges"
  [[ "$(login restaurantpro_app "$work/s1/db_app_password" 'SELECT count(*) FROM module.thing')" == 0 ]] \
    || fail "restaurantpro_app does not log in, or row-level security does not bind it"
  [[ "$(login restaurantpro_owner "$work/s1/db_owner_password" 'SELECT count(*) FROM module.thing')" == 1 ]] \
    || fail "restaurantpro_owner does not log in, or does not bypass row-level security"
  login restaurantpro_owner "$work/s1/db_owner_password" 'CREATE TABLE module.later (id int)' >/dev/null
  [[ "$(q -c "SELECT has_table_privilege('restaurantpro_app', 'module.later', 'SELECT, INSERT, UPDATE, DELETE')")" == t ]] \
    || fail "a table the owner creates later is not granted to restaurantpro_app (default privileges)"
  login restaurantpro_owner "$work/s1/db_owner_password" 'DROP TABLE module.later' >/dev/null
  ok "roles as D163; the owner owns every application object (not the extension's); the app owns nothing, has DML only, cannot write the Flyway history nor run a console function, and RLS binds it; default privileges cover later tables"

  echo "--- run 2 (db-provision again) and run 3 (--container, as restore.sh) change nothing" >&2
  as_service "$work/s1" >"$work/run2.out" 2>&1 || { cat "$work/run2.out" >&2; fail "the second run failed"; }
  local second
  second="$(state)"
  if [[ "$second" != "$first" ]]; then diff <(echo "$first") <(echo "$second") >&2 || true; fail "the second run changed the catalog"; fi
  "$SCRIPT_DIR/provision.sh" --container "$pg" --secrets "$work/s1" >"$work/run3.out" 2>&1 || { cat "$work/run3.out" >&2; fail "the --container run failed"; }
  no_secret_in "$work/run3.out" "$work/s1"
  local third
  third="$(state)"
  if [[ "$third" != "$first" ]]; then diff <(echo "$first") <(echo "$third") >&2 || true; fail "the --container run changed the catalog"; fi
  ok "the second and third runs leave roles, owners, ACLs, default ACLs, RLS and policies identical ($(wc -l <<<"$first" | tr -d ' ') catalog lines)"

  echo "--- a rotated password is re-synced" >&2
  cp "$work/s1/db_app_password" "$work/old_app_password"
  openssl rand -hex 24 >"$work/s1/db_app_password"
  as_service "$work/s1" >"$work/run4.out" 2>&1 || { cat "$work/run4.out" >&2; fail "the rotation run failed"; }
  [[ "$(login restaurantpro_app "$work/s1/db_app_password" 'SELECT 1')" == 1 ]] || fail "the new app password does not log in"
  if login restaurantpro_app "$work/old_app_password" 'SELECT 1' >/dev/null; then fail "the old app password still logs in"; fi
  ok "after a new db_app_password, the next run makes it the only one that logs in"

  echo "--- lib/roles.sh (the restore and drill check) passes the provisioned database and catches injected violations" >&2
  # shellcheck source=lib/roles.sh
  . "$SCRIPT_DIR/../lib/roles.sh"
  local problems
  problems="$(roles_problems "$pg" "$db")"
  [[ -z "$problems" ]] || fail "roles_problems on a provisioned database: $problems"
  caught() { # $1 violation sql, $2 revert sql, $3 expected text in roles_problems
    q -c "$1" >/dev/null
    problems="$(roles_problems "$pg" "$db")"
    q -c "$2" >/dev/null
    grep -qF "$3" <<<"$problems" || fail "roles_problems missed: $1 (got: ${problems:-nothing})"
  }
  caught 'GRANT CREATE ON SCHEMA module TO restaurantpro_app' 'REVOKE CREATE ON SCHEMA module FROM restaurantpro_app' \
    'restaurantpro_app can CREATE in schema module'
  caught 'GRANT CREATE ON SCHEMA public TO restaurantpro_app' 'REVOKE CREATE ON SCHEMA public FROM restaurantpro_app' \
    'restaurantpro_app can CREATE in schema public'
  caught 'ALTER TABLE module.thing DISABLE ROW LEVEL SECURITY' 'ALTER TABLE module.thing ENABLE ROW LEVEL SECURITY' \
    'module.thing has a policy but row-level security is disabled'
  caught 'GRANT restaurantpro_owner TO restaurantpro_app' 'REVOKE restaurantpro_owner FROM restaurantpro_app' \
    'restaurantpro_app is a member of restaurantpro_owner'
  caught 'GRANT INSERT ON public.flyway_schema_history TO restaurantpro_app' \
    'REVOKE INSERT ON public.flyway_schema_history FROM restaurantpro_app' 'restaurantpro_app can INSERT public.flyway_schema_history'
  [[ -z "$(roles_problems "$pg" "$db")" ]] || fail "a violation was not reverted"
  ok "roles_problems is clean after provisioning and reports CREATE on a schema, a policy without RLS, membership in the owner and a write on the Flyway history"

  if docker logs "$pg" >"$work/server.log" 2>&1; then no_secret_in "$work/server.log" "$work/s1"; no_secret_in "$work/server.log" "$work/wrong"; fi
  ok "no password in the server log"
  echo "PASS: provision.sh self-test" >&2
}

if $self_test; then self_test; else provision; fi
