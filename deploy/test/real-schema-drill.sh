#!/usr/bin/env bash
# Backup/restore drill against the REAL schema (acceptance criterion 10, PRD S6).
#
# test-backup-restore.sh proves the scripts on sample SQL. This one proves them on what the
# application really creates:
#   1. starts a throwaway PostgreSQL (same pin as production) and runs the backend jar against it,
#      so Flyway applies the real migrations and the bootstrap creates the company and the admin;
#   2. through the HTTP API: admin login (+CSRF), password change, two stores, a terminal enrolled and used
#      for a POS PIN login, an employee, an area and a table, a full catalog and a published release, so
#      every module's tables hold rows; keeps GET /backoffice/stores and the store's release list;
#   3. stops the backend, fingerprints every table (rows + md5) and the schema, runs backup.sh;
#   4. restores into a second fresh container with restore.sh and compares: identical data and schema,
#      except that the Spring Session tables come back empty;
#   5. runs the backend against the restored database: the old session is gone, the admin logs in again
#      with the changed password, and GET /backoffice/stores answers the same.
#
# Usage: deploy/test/real-schema-drill.sh [BACKEND_JAR]
#   BACKEND_JAR defaults to backend/target/backend-*.jar; build it first with
#   (cd backend && ./mvnw -q package -DskipTests). Needs Docker, Java 25, curl, openssl.
# Leaves nothing behind (containers, volumes, processes, temp files). Exits non-zero on any mismatch.
# Every credential is throwaway, generated per run, and never printed.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG_IMAGE="postgres:18.6-alpine" # D4
DB=restaurantpro
DB_USER=restaurantpro
RUN_ID="rp-schema-drill-$$-$(date +%s)"
SRC="$RUN_ID-src"
DST="$RUN_ID-dst"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-schema-drill.XXXXXX")"
STARTED=()
APP_PID=""

jar="${1:-}"
if [[ -z "$jar" ]]; then jar="$(ls "$DEPLOY_DIR"/../backend/target/backend-*.jar 2>/dev/null | grep -v plain | head -1 || true)"; fi

stop_app() {
  if [[ -n "$APP_PID" ]]; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
    APP_PID=""
  fi
}

cleanup() {
  local status=$?
  stop_app
  if ((${#STARTED[@]})); then docker rm -f -v "${STARTED[@]}" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: real-schema backup/restore drill" >&2; else echo "FAIL: real-schema backup/restore drill (exit $status)" >&2; fi
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo "--- $*" >&2; }

[[ -f "$jar" ]] || fail "no backend jar (got '$jar'): build it with (cd backend && ./mvnw -q package -DskipTests)"

free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }

start_pg() { # $1 name; the password file is shared so the app can use either container
  POSTGRES_PASSWORD="$(cat "$WORK/db_password")" docker run -d --name "$1" \
    -e POSTGRES_PASSWORD -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" \
    -p 127.0.0.1::5432 --label restaurantpro.drill="$RUN_ID" "$PG_IMAGE" >/dev/null
  STARTED+=("$1")
}

wait_pg() {
  local i
  for i in $(seq 1 60); do
    if docker exec "$1" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB"; then return 0; fi
    sleep 1
  done
  fail "$1 did not become ready"
}

psql_in() { docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "${@:2}"; }

# Same fingerprint as test-backup-restore.sh: one line per table (rows + md5 of all rows) and per sequence.
fingerprint() {
  psql_in "$1" -At <<'SQL'
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) FROM %I.%I t',
              n.nspname || '.' || c.relname, E'\n', '', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname, c.relname
\gexec
SELECT 'sequence ' || schemaname || '.' || sequencename, coalesce(last_value::text, 'unset') FROM pg_sequences ORDER BY 1;
SQL
}

schema_of() {
  docker exec "$1" pg_dump -U "$DB_USER" -d "$DB" --schema-only --no-owner --no-privileges \
    | grep -vE '^(--|\\(un)?restrict )' | sed '/^$/d'
}

without_casts() { sed -E 's/::(character varying|text)(\[\])?//g; s/[()]//g'; }

start_app() { # $1 container, $2 log name
  local pg_port
  pg_port="$(docker port "$1" 5432/tcp | head -1 | sed 's/.*://')"
  APP_PORT="$(free_port)"
  MGMT_PORT="$(free_port)"
  RESTAURANTPRO_DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/$DB?sslmode=disable" \
  RESTAURANTPRO_DB_USERNAME="$DB_USER" \
  RESTAURANTPRO_DB_PASSWORD="$(cat "$WORK/db_password")" \
  RESTAURANTPRO_MANAGEMENT_PORT="$MGMT_PORT" \
  SERVER_PORT="$APP_PORT" \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME="Drill Company" \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID="DRILL-1" \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY="MZ" \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY="MZN" \
  RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME="drilladmin" \
  RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE="$WORK/admin_initial_password" \
    java -jar "$jar" >"$WORK/$2.log" 2>&1 &
  APP_PID=$!
  local i
  for i in $(seq 1 120); do
    if curl -fsS "http://127.0.0.1:$MGMT_PORT/actuator/health" 2>/dev/null | grep -q '"UP"'; then return 0; fi
    kill -0 "$APP_PID" 2>/dev/null || { tail -40 "$WORK/$2.log" >&2; fail "backend exited during startup ($2)"; }
    sleep 1
  done
  tail -40 "$WORK/$2.log" >&2
  fail "backend did not become healthy ($2)"
}

# A browser-like client: cookie jar + the SPA CSRF pattern (XSRF-TOKEN cookie echoed in X-XSRF-TOKEN).
# curl sends Secure cookies to http://localhost (a secure context), as a browser behind Caddy's TLS would.
JAR=""
api() { # $1 method, $2 path, [$3 json body], [$4 extra "Name: value" header]; prints "<status>\n<body>"
  local token args=(-sS -o "$WORK/body" -w '%{http_code}' -b "$JAR" -c "$JAR" -X "$1" "http://localhost:$APP_PORT$2")
  token="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR" 2>/dev/null | tail -1)"
  if [[ "$1" != GET && -n "$token" ]]; then args+=(-H "X-XSRF-TOKEN: $token"); fi
  if (($# > 2)); then args+=(-H 'Content-Type: application/json' --data "$3"); fi
  if (($# > 3)); then args+=(-H "$4"); fi
  curl "${args[@]}"
  echo
  cat "$WORK/body"
}

expect() { # $1 expected status, rest = api args
  local out status
  out="$(api "${@:2}")"
  status="$(head -1 <<<"$out")"
  echo "    $2 $3 -> $status" >&2
  [[ "$status" == "$1" ]] || fail "$2 $3: expected $1, got $status: $(tail -n +2 <<<"$out")"
  tail -n +2 <<<"$out"
}

json_field() { python3 -c 'import json,sys; print(json.load(sys.stdin)[sys.argv[1]])' "$1"; }
# The store list without volatile paging details, ordered by code, as one canonical JSON line.
stores_view() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(json.dumps(sorted(d["content"], key=lambda s: s["code"]), sort_keys=True)), print(d["totalElements"])'; }

openssl rand -hex 16 | tr -d '\n' >"$WORK/db_password"
initial="initial-$(openssl rand -hex 12)"
changed="changed-$(openssl rand -hex 12)"
printf '%s\n' "$initial" >"$WORK/admin_initial_password"
login_body() { printf '{"username":"drilladmin","password":"%s"}' "$1"; }

step "starting throwaway containers $SRC and $DST ($PG_IMAGE)"
start_pg "$SRC"
start_pg "$DST"
wait_pg "$SRC"
wait_pg "$DST"

step "backend against $SRC: Flyway applies the real migrations, bootstrap creates company and admin"
start_app "$SRC" app-src
migrations="$(psql_in "$SRC" -At -c "SELECT count(*) FROM flyway_schema_history WHERE success")"
echo "    flyway migrations applied: $migrations" >&2
((migrations > 0)) || fail "no Flyway migration recorded"

step "API: admin login (+CSRF), password change, two stores"
JAR="$WORK/cookies-src"
expect 401 GET /api/v1/auth/session >/dev/null
for i in $(seq 1 30); do # the admin is created by an event listener just after the bootstrap commits
  [[ "$(api POST /api/v1/auth/login "$(login_body "$initial")" | head -1)" == 200 ]] && break
  ((i < 30)) || fail "admin login never succeeded"
  sleep 1
done
echo "    POST /api/v1/auth/login -> 200" >&2
expect 403 GET /api/v1/backoffice/stores >/dev/null # must change the password first
expect 200 POST /api/v1/auth/password "$(printf '{"currentPassword":"%s","newPassword":"%s"}' "$initial" "$changed")" >/dev/null
expect 201 POST /api/v1/backoffice/stores '{"code":"MAPUTO","name":"Maputo Baixa","timezone":"Africa/Maputo"}' >/dev/null
expect 201 POST /api/v1/backoffice/stores '{"code":"BEIRA","name":"Beira","timezone":"Africa/Maputo","businessDayCutoff":"04:00"}' >/dev/null
store="$(expect 201 POST /api/v1/backoffice/stores '{"code":"CHIMOIO","name":"Chimoio","timezone":"Africa/Maputo"}' | json_field id)"
terminal="$(expect 201 POST /api/v1/backoffice/terminals "{\"storeId\":\"$store\",\"code\":\"POS1\",\"name\":\"Bar\"}" | json_field id)"
code="$(expect 200 POST "/api/v1/backoffice/terminals/$terminal/enrollment-code" | json_field code)"
area="$(expect 201 POST /api/v1/backoffice/areas "{\"storeId\":\"$store\",\"code\":\"SALA\",\"name\":\"Sala\"}" | json_field id)"
expect 201 POST /api/v1/backoffice/tables "{\"storeId\":\"$store\",\"areaId\":\"$area\",\"code\":\"M1\",\"seats\":4}" >/dev/null
expect 201 POST /api/v1/backoffice/employees "{\"code\":\"W1\",\"name\":\"Ana\",\"pin\":\"4831\",\"storeId\":\"$store\",\"roleId\":\"01900000-0000-7000-8000-000000000303\"}" >/dev/null
C=/api/v1/backoffice/catalog
family="$(expect 201 POST $C/families '{"code":"BEB","name":"Bebidas"}' | json_field id)"
unit="$(expect 201 POST $C/units '{"code":"UN","name":"Unidade"}' | json_field id)"
tax="$(expect 201 POST $C/tax-categories '{"code":"IVA16","name":"IVA"}' | json_field id)"
expect 201 POST "$C/tax-categories/$tax/rates" '{"ratePercent":16,"effectiveFrom":"2000-01-01T00:00:00Z"}' >/dev/null
item="$(expect 201 POST $C/items "{\"code\":\"COCA33\",\"name\":\"Coca-Cola 33cl\",\"familyId\":\"$family\",\"unitId\":\"$unit\",\"taxCategoryId\":\"$tax\",\"productionDestination\":\"BAR\",\"soldByWeight\":false}" | json_field id)"
expect 201 POST "$C/items/$item/variants" '{"code":"GRANDE","name":"Grande"}' >/dev/null
expect 201 POST $C/barcodes "{\"code\":\"5449000000996\",\"itemId\":\"$item\"}" >/dev/null
list="$(expect 201 POST $C/price-lists "{\"storeId\":\"$store\",\"name\":\"Sala\"}" | json_field id)"
expect 201 POST "$C/price-lists/$list/prices" "{\"itemId\":\"$item\",\"amount\":45.00}" >/dev/null
release="$(expect 201 POST $C/releases "{\"storeId\":\"$store\"}" | json_field id)"
expect 200 POST "$C/releases/$release/validate" >/dev/null
expect 200 POST "$C/releases/$release/publish" '{"note":"drill"}' "Idempotency-Key: drill-$RUN_ID" >/dev/null
releases_before="$(expect 200 GET "$C/releases?storeId=$store" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"], sort_keys=True))')"
POS="$JAR"; JAR="$WORK/cookies-pos"
expect 401 GET /api/v1/auth/session >/dev/null
token="$(expect 200 POST /api/v1/auth/terminals/enroll "{\"code\":\"$code\"}" | json_field deviceToken)"
expect 200 POST /api/v1/auth/pos-login '{"employeeCode":"W1","pin":"4831"}' "X-Terminal-Token: $token" >/dev/null
expect 200 GET "/api/v1/pos/catalog?storeId=$store" >/dev/null
JAR="$POS"
stores_before="$(expect 200 GET /api/v1/backoffice/stores | stores_view)"
sessions="$(psql_in "$SRC" -At -c "SELECT count(*) FROM shared.spring_session")"
echo "    sessions in source: $sessions" >&2
((sessions > 0)) || fail "expected at least one live session in the source"

step "stop the backend, fingerprint the source, backup.sh"
stop_app
before="$(fingerprint "$SRC")"
source_schema="$(schema_of "$SRC")"
# Every table must hold rows, so the comparison covers each module; these few stay empty by design.
empty_ok='^public\.event_publication_archive$'
while IFS='|' read -r t rows _; do
  [[ "$t" == sequence* || "$rows" != 0 ]] && continue
  [[ "$t" =~ $empty_ok ]] || fail "source table $t is empty: the drill journey did not write it"
done <<<"$before"
expected_fp="$(sed -E 's/^(shared\.spring_session(_attributes)?)\|[0-9]+\|[0-9a-f]+$/\1|0|d41d8cd98f00b204e9800998ecf8427e/' <<<"$before")"
archive="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$WORK/backups" --keep 1)"
[[ -f "$archive" ]] || fail "backup.sh did not report an existing archive"

step "restore.sh into the fresh $DST and compare every table and the schema"
"$DEPLOY_DIR/restore.sh" --container "$DST" "$archive"
after="$(fingerprint "$DST")"
if [[ "$after" != "$expected_fp" ]]; then
  diff <(echo "$expected_fp") <(echo "$after") >&2 || true
  fail "restored data differs from the source"
fi
restored_schema="$(schema_of "$DST")"
if [[ "$restored_schema" != "$source_schema" ]]; then
  # PostgreSQL deparses a CHECK such as `status IN ('A','B')` on a varchar column as
  # `(status)::text = ANY ((ARRAY['A'::character varying, ...])::text[])`; re-parsing that text from the
  # dump stores the equivalent `ANY (ARRAY[('A'::character varying)::text, ...])`. Accept a difference
  # only when it vanishes once those casts and parentheses are dropped, and say how many lines it was.
  if [[ "$(without_casts <<<"$restored_schema")" != "$(without_casts <<<"$source_schema")" ]]; then
    diff <(echo "$source_schema") <(echo "$restored_schema") >&2 || true
    fail "restored schema differs from the source"
  fi
  echo "    schema identical except $(diff <(echo "$source_schema") <(echo "$restored_schema") | grep -c '^>') CHECK line(s) re-deparsed with equivalent casts" >&2
else
  echo "    schema identical" >&2
fi
sed 's/^/    /' <<<"$after" >&2
grep -q '^shared.spring_session|0|' <<<"$after" || fail "sessions were restored"

step "backend against the restored $DST: sessions are gone, same GET /backoffice/stores"
start_app "$DST" app-dst
expect 401 GET /api/v1/backoffice/stores >/dev/null # the old SESSION cookie no longer exists
JAR="$WORK/cookies-dst"
expect 401 GET /api/v1/auth/session >/dev/null
expect 200 POST /api/v1/auth/login "$(login_body "$changed")" >/dev/null
stores_after="$(expect 200 GET /api/v1/backoffice/stores | stores_view)"
releases_after="$(expect 200 GET "$C/releases?storeId=$store" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"], sort_keys=True))')"
[[ "$releases_after" == "$releases_before" ]] || fail "the published release differs after the restore"
echo "    GET /backoffice/catalog/releases identical (published release, version and author)" >&2
if [[ "$stores_after" != "$stores_before" ]]; then
  diff <(echo "$stores_before") <(echo "$stores_after") >&2 || true
  fail "GET /backoffice/stores differs after the restore"
fi
echo "    GET /backoffice/stores identical: $(tail -1 <<<"$stores_after") stores" >&2
