#!/usr/bin/env bash
# Backup/restore drill against the REAL schema (acceptance criterion 10, PRD S6).
#
# test-backup-restore.sh proves the scripts on sample SQL. This one proves them on what the
# application really creates:
#   1. starts a throwaway PostgreSQL (same pin as production) and runs the backend jar against it,
#      so Flyway applies the real migrations and the bootstrap creates the company and the admin;
#   2. through the HTTP API only: admin login (+CSRF), password change, two stores, a terminal enrolled and used
#      for a POS PIN login, an employee, areas and tables, a full catalog and a published release; then the R2
#      service of a table (a printer, a route, the terminal's printer, a register, a fiscal series, a card
#      method; a cash session, two orders, rounds, a transfer that cancels the emptied order, a void with an
#      approver, a subtotal, cash with change, a confirmed card payment, the document and a reprint, the order
#      closed, the session counted, the closing approved, the day closed, reopened with an approver, closed
#      again and closed financially); and the R3 paths (a customer with a phone, an address and a credit limit;
#      an ITEMS partition invoiced on its own and a settled void, whose tax cent becomes a pending correction
#      that is then resolved; a sale on account; a debt payment and a refund of part of it; a card statement
#      imported and a mismatch resolved; a support account and grant; a report rebuild), so every module's
#      tables hold rows (the drill fails listing every table left empty); keeps GET /backoffice/stores, the
#      store's release list, its fiscal document list, the tax report and the customer's statement;
#   3. stops the backend, fingerprints every table (rows + md5) and the schema, runs backup.sh;
#   4. restores into a second fresh container with restore.sh and compares: identical data and schema,
#      except that the Spring Session tables come back empty;
#   5. runs the backend against the restored database: the old session is gone, the admin logs in again
#      with the changed password, and GET /backoffice/stores, the releases, the documents, the tax report and
#      the customer statement answer the same.
#
# Usage: deploy/test/real-schema-drill.sh [BACKEND_JAR]
#   BACKEND_JAR defaults to backend/target/backend-*.jar; build it first with
#   (cd backend && ./mvnw -q package -DskipTests). Needs Docker, Java 25, curl, openssl, python3.
# Takes about 1-2 minutes (two backend starts). The printer points at a port nothing listens on, so the print
# jobs stay queued for retry, as they are in any backup taken while a printer is down.
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
if [[ -z "$jar" ]]; then
  for candidate in "$DEPLOY_DIR"/../backend/target/backend-*.jar; do
    if [[ -f "$candidate" && "$candidate" != *plain* ]]; then jar="$candidate"; break; fi
  done
fi

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
die() { fail "$@"; }
step() { echo "--- $*" >&2; }
# D98: production mode with throwaway keys (both recipients), as an installation runs.
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=test/keys.sh
. "$DEPLOY_DIR/test/keys.sh"
mkdir -m 700 "$WORK/keys"
make_throwaway_keys "$WORK/keys"

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

# Normalizes only the re-deparse: drops the varchar/text casts, then the parentheses those casts wrapped, around
# one literal `('A')` and around the array `((ARRAY[...]))`. Grouping parentheses, and so AND/OR precedence,
# stay as they are (e.g. fiscal_document_issued_ck mixes OR with a grouped AND).
without_casts() {
  sed -E "s/::(character varying|text)(\[\])?//g; s/\(('[^']*')\)/\1/g; s/\(\((ARRAY\[[^]]*\])\)\)/(\1)/g"
}
# A schema line whose expression PostgreSQL may re-deparse with equivalent casts: a CHECK, or a partial index.
redeparsable() { [[ "$1" == *"CHECK ("* || ("$1" == "CREATE "*"INDEX "* && "$1" == *" WHERE "*) ]]; }

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
  RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED=true \
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
# Every write carries a fresh Idempotency-Key (as the SPA does, spec §9) unless an extra header brings one.
api() { # $1 method, $2 path, [$3 json body ('' = none)], [$4... extra "Name: value" headers]; prints "<status>\n<body>"
  local token header keyed="" args=(-sS -o "$WORK/body" -w '%{http_code}' -b "$JAR" -c "$JAR" -X "$1" "http://localhost:$APP_PORT$2")
  token="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR" 2>/dev/null | tail -1)"
  if [[ "$1" != GET && -n "$token" ]]; then args+=(-H "X-XSRF-TOKEN: $token"); fi
  if (($# > 2)) && [[ -n "$3" ]]; then args+=(-H 'Content-Type: application/json' --data "$3"); fi
  for header in "${@:4}"; do
    args+=(-H "$header")
    [[ "$header" == Idempotency-Key:* ]] && keyed=1
  done
  if [[ "$1" != GET && -z "$keyed" ]]; then args+=(-H "Idempotency-Key: $(openssl rand -hex 16)"); fi
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
# A nested field by dotted path, e.g. json_path cashDayId or json_path lines.0.id.
json_path() { python3 -c $'import json,sys\nd=json.load(sys.stdin)\nfor k in sys.argv[1].split("."):\n    d=d[int(k)] if k.isdigit() else d[k]\nprint(d)' "$1"; }
# The order's current version, as the POS reads it before a versioned command.
order_version() { expect 200 GET "/api/v1/pos/orders/$1" | json_field version; }
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
table1="$(expect 201 POST /api/v1/backoffice/tables "{\"storeId\":\"$store\",\"areaId\":\"$area\",\"code\":\"M1\",\"seats\":4}" | json_field id)"
table2="$(expect 201 POST /api/v1/backoffice/tables "{\"storeId\":\"$store\",\"areaId\":\"$area\",\"code\":\"M2\",\"seats\":2}" | json_field id)"
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
expect 201 POST /api/v1/backoffice/fiscal/series "{\"storeId\":\"$store\",\"code\":\"A\",\"firstNumber\":1}" >/dev/null
# R2 BackOffice setup: a printer nothing listens on, the BAR route, the second terminal's printer, a register,
# a card method, a manager who operates the POS and a second manager who approves.
printer_port="$(free_port)"
printer="$(expect 201 POST /api/v1/backoffice/printers "{\"storeId\":\"$store\",\"code\":\"BAR\",\"name\":\"Bar\",\"host\":\"127.0.0.1\",\"port\":$printer_port,\"codePage\":\"CP860\"}" | json_field id)"
expect 200 PUT /api/v1/backoffice/production-routes "{\"storeId\":\"$store\",\"routes\":[{\"destinationCode\":\"BAR\",\"printerId\":\"$printer\"}]}" >/dev/null
terminal2="$(expect 201 POST /api/v1/backoffice/terminals "{\"storeId\":\"$store\",\"code\":\"POS2\",\"name\":\"Sala\"}" | json_field id)"
code2="$(expect 200 POST "/api/v1/backoffice/terminals/$terminal2/enrollment-code" | json_field code)"
expect 200 PUT /api/v1/backoffice/terminal-printers "{\"storeId\":\"$store\",\"assignments\":[{\"terminalId\":\"$terminal2\",\"printerId\":\"$printer\"}]}" >/dev/null
register="$(expect 201 POST /api/v1/backoffice/cash/registers "{\"storeId\":\"$store\",\"code\":\"CX1\",\"name\":\"Caixa 1\"}" | json_field id)"
expect 201 POST /api/v1/backoffice/payment-methods '{"code":"CARD","name":"Cartao","kind":"EXTERNAL_TERMINAL","providerCode":null}' >/dev/null
MANAGER_ROLE=01900000-0000-7000-8000-000000000302
expect 201 POST /api/v1/backoffice/employees "{\"code\":\"G1\",\"name\":\"Gil\",\"pin\":\"5172\",\"storeId\":\"$store\",\"roleId\":\"$MANAGER_ROLE\"}" >/dev/null
expect 201 POST /api/v1/backoffice/employees "{\"code\":\"G2\",\"name\":\"Rita\",\"pin\":\"6283\",\"storeId\":\"$store\",\"roleId\":\"$MANAGER_ROLE\"}" >/dev/null
APPROVER=("X-Approver-Code: G2" "X-Approver-Pin: 6283")
releases_before="$(expect 200 GET "$C/releases?storeId=$store" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"], sort_keys=True))')"
POS="$JAR"; JAR="$WORK/cookies-pos"
expect 401 GET /api/v1/auth/session >/dev/null
token="$(expect 200 POST /api/v1/auth/terminals/enroll "{\"code\":\"$code\"}" | json_field deviceToken)"
expect 200 POST /api/v1/auth/pos-login '{"employeeCode":"W1","pin":"4831"}' "X-Terminal-Token: $token" >/dev/null
expect 200 GET "/api/v1/pos/catalog?storeId=$store" >/dev/null

step "API (R2): a table served, paid, documented and closed; the session counted; the day closed twice"
JAR="$WORK/cookies-pos2"
expect 401 GET /api/v1/auth/session >/dev/null
token2="$(expect 200 POST /api/v1/auth/terminals/enroll "{\"code\":\"$code2\"}" | json_field deviceToken)"
expect 200 POST /api/v1/auth/pos-login '{"employeeCode":"G1","pin":"5172"}' "X-Terminal-Token: $token2" >/dev/null
session_json="$(expect 201 POST /api/v1/pos/cash/sessions "{\"registerId\":\"$register\",\"openingFloat\":0}")"
session_id="$(json_field id <<<"$session_json")"
cash_day="$(json_path cashDayId <<<"$session_json")"
catalog_version="$(expect 200 GET "/api/v1/pos/catalog?storeId=$store" | json_field version)"
order_a="$(expect 201 POST "/api/v1/pos/tables/$table1/open" '{"guests":2}' | json_field id)"
line_a="$(expect 201 POST "/api/v1/pos/orders/$order_a/lines" "{\"itemId\":\"$item\",\"quantity\":2}" | json_field id)"
expect 201 POST "/api/v1/pos/orders/$order_a/batches" "{\"orderVersion\":$(order_version "$order_a"),\"catalogVersion\":$catalog_version,\"lineIds\":[\"$line_a\"]}" >/dev/null
order_b="$(expect 201 POST "/api/v1/pos/tables/$table2/open" '{"guests":1}' | json_field id)"
line_b="$(expect 201 POST "/api/v1/pos/orders/$order_b/lines" "{\"itemId\":\"$item\",\"quantity\":1}" | json_field id)"
expect 201 POST "/api/v1/pos/orders/$order_b/batches" "{\"orderVersion\":$(order_version "$order_b"),\"catalogVersion\":$catalog_version,\"lineIds\":[\"$line_b\"]}" >/dev/null
expect 201 POST "/api/v1/pos/orders/$order_b/transfers" "{\"orderVersion\":$(order_version "$order_b"),\"targetOrderId\":\"$order_a\",\"targetOrderVersion\":$(order_version "$order_a"),\"lines\":[{\"lineId\":\"$line_b\",\"quantity\":1}],\"cancelSourceWhenEmpty\":true}" >/dev/null
expect 201 POST "/api/v1/pos/orders/$order_a/voids" "{\"orderVersion\":$(order_version "$order_a"),\"lineId\":\"$line_a\",\"quantity\":1,\"reason\":\"drill\",\"wasted\":true}" "${APPROVER[@]}" >/dev/null
expect 202 POST "/api/v1/pos/orders/$order_a/subtotal/print" >/dev/null
expect 201 POST /api/v1/pos/payment-attempts "{\"orderId\":\"$order_a\",\"methodCode\":\"CASH\",\"amount\":50.00,\"tendered\":100.00}" >/dev/null
card="$(expect 201 POST /api/v1/pos/payment-attempts "{\"orderId\":\"$order_a\",\"methodCode\":\"CARD\",\"amount\":40.00}" | json_field id)"
expect 200 POST "/api/v1/pos/payment-attempts/$card/confirm" '{"authorizationCode":"DRILL-1"}' >/dev/null
document_json="$(expect 201 POST "/api/v1/pos/orders/$order_a/documents" '{}')"
document="$(json_field id <<<"$document_json")"
[[ "$(json_field number <<<"$document_json")" == 1 && "$(json_field status <<<"$document_json")" == ISSUED ]] \
  || fail "the first document of series A is not ISSUED number 1: $document_json"
expect 201 POST "/api/v1/pos/documents/$document/reprints" >/dev/null
close_version="$(expect 200 GET "/api/v1/pos/orders/$order_a/settlement" | json_field orderVersion)"
expect 200 POST "/api/v1/pos/orders/$order_a/close" "{\"orderVersion\":$close_version}" >/dev/null
step "API (R3): a customer on credit, a split with a settled void (D96 pending correction, resolved), a debt payment"
step "         and its refund, a card statement reconciled, a support grant and a report rebuild"
POS2="$JAR"; JAR="$POS"
customer="$(expect 201 POST /api/v1/backoffice/customers '{"name":"Rui Tembe","phone":"842345678","taxId":"100200300"}' | json_field id)"
expect 201 POST "/api/v1/backoffice/customers/$customer/phones" '{"number":"823456789","label":"trabalho","primary":false}' >/dev/null
expect 201 POST "/api/v1/backoffice/customers/$customer/addresses" '{"kind":"BILLING","line1":"Av. 25 de Setembro 100","city":"Chimoio","country":"MZ"}' >/dev/null
expect 200 POST "/api/v1/backoffice/customers/$customer/credit-limit-changes" '{"newLimit":200.00,"reason":"drill: a regular customer"}' >/dev/null
JAR="$POS2"
# D96: 2 x 45.00 at 16 %, one unit in a partition invoiced on its own (share tax 6.20), the other voided after a
# payment (the line's tax is now 6.21): nothing on the order can absorb the cent, so the close records a pending
# correction naming the partition's document, which stays unchanged.
order_c="$(expect 201 POST "/api/v1/pos/tables/$table2/open" '{"guests":2}' | json_field id)"
line_c="$(expect 201 POST "/api/v1/pos/orders/$order_c/lines" "{\"itemId\":\"$item\",\"quantity\":2}" | json_field id)"
expect 201 POST "/api/v1/pos/orders/$order_c/batches" "{\"orderVersion\":$(order_version "$order_c"),\"catalogVersion\":$catalog_version,\"lineIds\":[\"$line_c\"]}" >/dev/null
partition="$(expect 201 POST "/api/v1/pos/orders/$order_c/partitions" "{\"orderVersion\":$(order_version "$order_c"),\"kind\":\"ITEMS\",\"lines\":[{\"lineId\":\"$line_c\",\"quantity\":1}]}" | json_field id)"
expect 201 POST /api/v1/pos/payment-attempts "{\"orderId\":\"$order_c\",\"partitionId\":\"$partition\",\"methodCode\":\"CASH\",\"amount\":45.00,\"tendered\":50.00}" >/dev/null
expect 201 POST "/api/v1/pos/partitions/$partition/documents" '{}' >/dev/null
expect 201 POST "/api/v1/pos/orders/$order_c/settlement/voids" "{\"orderVersion\":$(order_version "$order_c"),\"lineId\":\"$line_c\",\"quantity\":1,\"reason\":\"drill: one too many\",\"wasted\":false}" "${APPROVER[@]}" >/dev/null
close_c="$(expect 200 GET "/api/v1/pos/orders/$order_c/settlement" | json_field orderVersion)"
expect 200 POST "/api/v1/pos/orders/$order_c/close" "{\"orderVersion\":$close_c}" >/dev/null
# A sale on account (reserve, document, debit), then part of the debt paid in cash and 5.00 of it refunded.
order_d="$(expect 201 POST "/api/v1/pos/tables/$table1/open" '{"guests":1}' | json_field id)"
line_d="$(expect 201 POST "/api/v1/pos/orders/$order_d/lines" "{\"itemId\":\"$item\",\"quantity\":1}" | json_field id)"
expect 201 POST "/api/v1/pos/orders/$order_d/batches" "{\"orderVersion\":$(order_version "$order_d"),\"catalogVersion\":$catalog_version,\"lineIds\":[\"$line_d\"]}" >/dev/null
expect 201 POST "/api/v1/pos/orders/$order_d/credit-charges" "{\"customerId\":\"$customer\",\"amount\":45.00}" >/dev/null
close_d="$(expect 200 GET "/api/v1/pos/orders/$order_d/settlement" | json_field orderVersion)"
expect 200 POST "/api/v1/pos/orders/$order_d/close" "{\"orderVersion\":$close_d}" >/dev/null
debt_attempt="$(expect 201 POST "/api/v1/pos/customers/$customer/credit-payments" '{"methodCode":"CASH","amount":20.00,"tendered":20.00}' | json_field id)"
debt_payment="$(psql_in "$SRC" -At -c "SELECT id FROM settlement.payment WHERE attempt_id = '$debt_attempt'")"
expect 201 POST "/api/v1/pos/payments/$debt_payment/refunds" '{"amount":5.00,"reason":"drill: overcharged"}' "${APPROVER[@]}" >/dev/null
JAR="$POS"
correction="$(expect 200 GET "/api/v1/backoffice/reconciliation/tax-corrections?storeId=$store&open=true" | json_path content.0.id)"
expect 201 POST "/api/v1/backoffice/reconciliation/tax-corrections/$correction/resolutions" '{"correctionReference":"NC-DRILL-1","reason":"drill: corrective document issued"}' >/dev/null
# The card statement: the R2 card payment (MATCHED, auto-resolved) and a line nobody here took (resolved by hand).
card_at="$(psql_in "$SRC" -At -c "SELECT to_char(confirmed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM settlement.payment WHERE method_code = 'CARD'")"
printf 'occurred_at,amount,fee,reference,authorization_code,line_ref\n%s,40.00,0.60,,DRILL-1,L1\n%s,12.00,0.18,,UNKNOWN-9,L2\n' "$card_at" "$card_at" >"$WORK/statement.csv"
today="$(TZ=Africa/Maputo date +%F)"
from_day="$(python3 -c 'import datetime,sys; d=datetime.date.fromisoformat(sys.argv[1]); print(d - datetime.timedelta(days=1))' "$today")"
to_day="$(python3 -c 'import datetime,sys; d=datetime.date.fromisoformat(sys.argv[1]); print(d + datetime.timedelta(days=1))' "$today")"
xsrf="$(awk '$6 == "XSRF-TOKEN" {print $7}' "$JAR" | tail -1)"
batch_status="$(curl -sS -o "$WORK/body" -w '%{http_code}' -b "$JAR" -c "$JAR" -H "X-XSRF-TOKEN: $xsrf" \
  -H "Idempotency-Key: $(openssl rand -hex 16)" -F "file=@$WORK/statement.csv;type=text/csv" \
  "http://localhost:$APP_PORT/api/v1/backoffice/reconciliation/batches?storeId=$store&methodCode=CARD&periodFrom=$from_day&periodTo=$to_day")"
echo "    POST /api/v1/backoffice/reconciliation/batches -> $batch_status" >&2
[[ "$batch_status" == 201 ]] || fail "statement import: expected 201, got $batch_status: $(cat "$WORK/body")"
batch="$(json_field id <"$WORK/body")"
missing="$(expect 200 GET "/api/v1/backoffice/reconciliation/batches/$batch/entries?kind=MISSING_INTERNAL" | json_path content.0.id)"
expect 201 POST "/api/v1/backoffice/reconciliation/entries/$missing/resolutions" '{"action":"ESCALATED","reason":"drill: not ours"}' >/dev/null
support="$(expect 201 POST /api/v1/backoffice/users "{\"username\":\"drill.support\",\"initialPassword\":\"support-$(openssl rand -hex 12)\",\"roleIds\":[],\"support\":true}" | json_field id)"
expect 201 POST /api/v1/backoffice/support-grants "{\"accountId\":\"$support\",\"durationMinutes\":60,\"reason\":\"drill: support\"}" >/dev/null
expect 202 POST /api/v1/backoffice/reports/rebuild "{\"storeId\":\"$store\",\"from\":\"$from_day\",\"to\":\"$to_day\"}" >/dev/null
for i in $(seq 1 60); do # the rebuild runs off the request; wait for it to finish before the backup
  rebuild_status="$(expect 200 GET "/api/v1/backoffice/reports/status?storeId=$store" | json_path lastRebuild.status)"
  [[ "$rebuild_status" != RUNNING ]] && break
  sleep 1
done
[[ "$rebuild_status" == COMPLETED ]] || fail "the report rebuild ended $rebuild_status"
taxes_before="$(expect 200 GET "/api/v1/backoffice/reports/taxes?storeId=$store&from=$from_day&to=$to_day" | python3 -c 'import json,sys; d=json.load(sys.stdin); d.pop("asOf"); print(json.dumps(d, sort_keys=True))')"
statement_before="$(expect 200 GET "/api/v1/backoffice/customers/$customer/statement" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["lines"], sort_keys=True))')"
JAR="$POS2"
# The session is counted as expected: the R2 sale, the partition, the debt payment less its refund.
session_json="$(expect 200 GET "/api/v1/pos/cash/sessions/$session_id")"
session_version="$(json_field version <<<"$session_json")"
counts="$(python3 -c 'import json,sys; print(json.dumps([{"methodCode": e["methodCode"], "amount": e["amount"]["amount"]} for e in json.load(sys.stdin)["expected"]]))' <<<"$session_json")"
expect 200 POST "/api/v1/pos/cash/sessions/$session_id/close" "{\"version\":$session_version,\"counts\":$counts}" >/dev/null
closing="$(expect 201 POST /api/v1/pos/cash/closings "{\"registerId\":\"$register\",\"cashDayId\":\"$cash_day\"}" | json_field id)"
expect 200 POST "/api/v1/pos/cash/closings/$closing/submit" >/dev/null
POS2="$JAR"; JAR="$POS"
expect 200 POST "/api/v1/backoffice/cash/closings/$closing/approve" >/dev/null
JAR="$POS2"
expect 200 POST "/api/v1/pos/cash/days/$cash_day/operational-close" "{\"version\":$(expect 200 GET /api/v1/pos/cash/days/current | json_field version)}" >/dev/null
JAR="$POS"
expect 200 POST "/api/v1/backoffice/cash/days/$cash_day/reopen" '{"reason":"drill: a late correction"}' "${APPROVER[@]}" >/dev/null
JAR="$POS2"
expect 200 POST "/api/v1/pos/cash/days/$cash_day/operational-close" "{\"version\":$(expect 200 GET /api/v1/pos/cash/days/current | json_field version)}" >/dev/null
JAR="$POS"
expect 200 POST "/api/v1/backoffice/cash/days/$cash_day/financial-close" >/dev/null
documents_before="$(expect 200 GET "/api/v1/backoffice/fiscal/documents?storeId=$store" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"], sort_keys=True))')"
# One failed BackOffice login under an unknown username: its throttle row (§12 of the decisions) stays.
JAR="$WORK/cookies-intruder"
expect 401 GET /api/v1/auth/session >/dev/null
expect 401 POST /api/v1/auth/login '{"username":"intruder","password":"wrong-password"}' >/dev/null
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
empty=()
while IFS='|' read -r t rows _; do
  [[ "$t" == sequence* || "$rows" != 0 ]] && continue
  [[ "$t" =~ $empty_ok ]] || empty+=("$t")
done <<<"$before"
((${#empty[@]} == 0)) || fail "source tables are empty, the drill journey did not write them: ${empty[*]}"
expected_fp="$(sed -E 's/^(shared\.spring_session(_attributes)?)\|[0-9]+\|[0-9a-f]+$/\1|0|d41d8cd98f00b204e9800998ecf8427e/' <<<"$before")"
archive="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$WORK/backups" --keep 1)"
[[ -f "$archive" && "$archive" == *.tar.age ]] || fail "backup.sh did not report an encrypted archive (got '$archive')"
assert_no_plaintext "$WORK/backups" || fail "plaintext in the backup directory"

step "restore.sh into the fresh $DST and compare every table and the schema"
"$DEPLOY_DIR/restore.sh" --container "$DST" --identity "$WORK/keys/operational.key" "$archive"
after="$(fingerprint "$DST")"
if [[ "$after" != "$expected_fp" ]]; then
  diff <(echo "$expected_fp") <(echo "$after") >&2 || true
  fail "restored data differs from the source"
fi
restored_schema="$(schema_of "$DST")"
# PostgreSQL deparses a CHECK such as `status IN ('A','B')` on a varchar column as
# `(status)::text = ANY ((ARRAY['A'::character varying, ...])::text[])`; re-parsing that text from the
# dump stores the equivalent `ANY (ARRAY[('A'::character varying)::text, ...])`. The predicate of a partial
# index (e.g. cash.cash_closing_live_uk, `WHERE status IN (...)`) is deparsed the same way. So compare line by
# line: a line may differ only when both versions are CHECK constraints, or both are the same partial index,
# that become equal once those casts and the parentheses they wrapped are dropped (without_casts). Anything
# else (a missing, extra or other changed line) fails.
if [[ "$(wc -l <<<"$restored_schema")" != "$(wc -l <<<"$source_schema")" ]]; then
  diff <(echo "$source_schema") <(echo "$restored_schema") >&2 || true
  fail "restored schema differs from the source (line count)"
fi
redeparsed=0
while IFS= read -r src_line <&3 && IFS= read -r dst_line <&4; do
  [[ "$src_line" == "$dst_line" ]] && continue
  if ! redeparsable "$src_line" || ! redeparsable "$dst_line" \
        || [[ "$(without_casts <<<"$src_line")" != "$(without_casts <<<"$dst_line")" ]]; then
    printf 'source:   %s\nrestored: %s\n' "$src_line" "$dst_line" >&2
    fail "restored schema differs from the source"
  fi
  redeparsed=$((redeparsed + 1))
done 3<<<"$source_schema" 4<<<"$restored_schema"
if ((redeparsed)); then
  echo "    schema identical except $redeparsed CHECK/partial-index line(s) re-deparsed with equivalent casts" >&2
else
  echo "    schema identical" >&2
fi
while IFS= read -r line; do echo "    $line"; done <<<"$after" >&2
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
documents_after="$(expect 200 GET "/api/v1/backoffice/fiscal/documents?storeId=$store" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["content"], sort_keys=True))')"
[[ "$documents_after" == "$documents_before" ]] || fail "the fiscal document list differs after the restore"
echo "    GET /backoffice/fiscal/documents identical (the issued document, its number and totals)" >&2
taxes_after="$(expect 200 GET "/api/v1/backoffice/reports/taxes?storeId=$store&from=$from_day&to=$to_day" | python3 -c 'import json,sys; d=json.load(sys.stdin); d.pop("asOf"); print(json.dumps(d, sort_keys=True))')"
[[ "$taxes_after" == "$taxes_before" ]] || fail "the tax report differs after the restore"
echo "    GET /backoffice/reports/taxes identical (R3 projection)" >&2
statement_after="$(expect 200 GET "/api/v1/backoffice/customers/$customer/statement" | python3 -c 'import json,sys; print(json.dumps(json.load(sys.stdin)["lines"], sort_keys=True))')"
[[ "$statement_after" == "$statement_before" ]] || fail "the customer statement differs after the restore"
echo "    GET /backoffice/customers/{id}/statement identical (R3 credit ledger, FIFO settlement, refund reversal)" >&2
if [[ "$stores_after" != "$stores_before" ]]; then
  diff <(echo "$stores_before") <(echo "$stores_after") >&2 || true
  fail "GET /backoffice/stores differs after the restore"
fi
echo "    GET /backoffice/stores identical: $(tail -1 <<<"$stores_after") stores" >&2
