#!/usr/bin/env bash
# Restore drill (PRD S5, acceptance criterion 10; D32).
#
# Starts two throwaway PostgreSQL containers, loads deploy/test/sample.sql into the first,
# runs backup.sh against it, restores the archive into the second with restore.sh, and
# compares a per-table row count + checksum and the sequence positions. Also checks that
# retention keeps N archives, that restore.sh refuses a non-empty target without --force,
# and that a corrupted archive is rejected. Exits non-zero on any mismatch.
#
# Usage: deploy/test-backup-restore.sh        (needs only Docker; leaves nothing behind)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PG_IMAGE="postgres:18.6-alpine" # D4: same pin as deploy/compose.yaml and backend tests
DB=restaurantpro
DB_USER=restaurantpro
RUN_ID="rp-drill-$$-$(date +%s)"
SRC="$RUN_ID-src"
DST="$RUN_ID-dst"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-drill.XXXXXX")"
STARTED=()

cleanup() {
  local status=$?
  if ((${#STARTED[@]})); then
    docker rm -f -v "${STARTED[@]}" >/dev/null 2>&1 || true
  fi
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: backup/restore drill" >&2; else echo "FAIL: backup/restore drill (exit $status)" >&2; fi
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
step() { echo "--- $*" >&2; }

start_pg() {
  local name=$1
  # Throwaway password, passed by variable name so it never appears on a command line or in output.
  POSTGRES_PASSWORD="$(openssl rand -hex 16)" docker run -d --name "$name" \
    -e POSTGRES_PASSWORD -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" \
    --label restaurantpro.drill="$RUN_ID" "$PG_IMAGE" >/dev/null
  STARTED+=("$name")
}

wait_pg() {
  local name=$1 i
  # -h 127.0.0.1: the image's init-time server listens on the socket only, so TCP readiness
  # means the final server is up.
  for i in $(seq 1 60); do
    if docker exec "$name" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB"; then return 0; fi
    sleep 1
  done
  fail "$name did not become ready (attempts: $i)"
}

psql_in() { docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "${@:2}"; }

# One line per user table ("schema.table|rows|md5 of all rows in a stable order") and per sequence.
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

step "starting throwaway containers $SRC and $DST ($PG_IMAGE)"
start_pg "$SRC"
start_pg "$DST"
wait_pg "$SRC"
wait_pg "$DST"

step "loading sample data"
psql_in "$SRC" <"$DEPLOY_DIR/test/sample.sql"
before="$(fingerprint "$SRC")"
grep -q '^organization.price_sample|5000|' <<<"$before" || fail "sample data not loaded as expected"

step "backup with retention 2 (two older archives already present)"
mkdir -p "$WORK/backups"
touch "$WORK/backups/restaurantpro-20000101T000000Z.tar" "$WORK/backups/restaurantpro-20000102T000000Z.tar"
archive="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$WORK/backups" --keep 2)"
[[ -f "$archive" ]] || fail "backup.sh did not report an existing archive (got '$archive')"
kept="$(cd "$WORK/backups" && ls)"
expected="$(printf '%s\n' restaurantpro-20000102T000000Z.tar "$(basename "$archive")")"
[[ "$kept" == "$expected" ]] || fail "retention kept: $(tr '\n' ' ' <<<"$kept"); expected: $(tr '\n' ' ' <<<"$expected")"

step "corrupted archive is rejected before touching the target"
cp "$archive" "$WORK/corrupt.tar"
# Flip bytes in the middle of the archive: inside db.dump, which is nearly all of it.
printf 'garbage' | dd of="$WORK/corrupt.tar" bs=1 seek=$(($(wc -c <"$archive") / 2)) conv=notrunc status=none
if "$DEPLOY_DIR/restore.sh" --container "$DST" "$WORK/corrupt.tar" 2>"$WORK/corrupt.err"; then fail "restore.sh accepted a corrupted archive"; fi
grep -q 'checksum mismatch' "$WORK/corrupt.err" || fail "corruption not caught by the checksum check: $(cat "$WORK/corrupt.err")"
[[ "$(psql_in "$DST" -At -c "SELECT count(*) FROM pg_namespace WHERE nspname = 'organization'")" == 0 ]] \
  || fail "corrupted archive changed the target"

step "restore into the fresh container"
"$DEPLOY_DIR/restore.sh" --container "$DST" "$archive"
after="$(fingerprint "$DST")"
if [[ "$before" != "$after" ]]; then
  diff <(echo "$before") <(echo "$after") >&2 || true
  fail "restored data differs from the source"
fi
while IFS= read -r line; do echo "    $line" >&2; done <<<"$after"

step "append-only trigger survived the restore"
if psql_in "$DST" -c "UPDATE audit.audit_event SET action = 'x' WHERE id = 1" 2>/dev/null; then
  fail "audit trigger missing after restore"
fi

step "non-empty target is refused without --force, replaced with --force"
if "$DEPLOY_DIR/restore.sh" --container "$DST" "$archive" 2>/dev/null; then fail "restore.sh overwrote a non-empty target without --force"; fi
psql_in "$DST" -c "INSERT INTO organization.price_sample (store_id, amount) SELECT id, 1 FROM organization.store LIMIT 1"
"$DEPLOY_DIR/restore.sh" --container "$DST" --force "$archive"
[[ "$(fingerprint "$DST")" == "$before" ]] || fail "--force restore did not reproduce the source"
