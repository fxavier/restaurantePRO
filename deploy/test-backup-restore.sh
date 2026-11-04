#!/usr/bin/env bash
# Restore drill (PRD S5, acceptance criterion 10; D32).
#
# Starts two throwaway PostgreSQL containers, loads deploy/test/sample.sql into the first,
# runs backup.sh against it, restores the archive into the second with restore.sh, and
# compares per-table row counts + checksums, sequence positions and the normalised schema
# (pg_dump --schema-only). Spring Session rows must come back empty (not backed up).
# Also checks:
#   - retention keeps the newest N timestamped archives and ignores hand-named files
#   - a corrupted archive is rejected by the checksum check, target untouched
#   - a non-empty target is refused without --force, target untouched
#   - --force replaces the target while a client holds a session on it, and removes objects
#     created after the backup (rollback after an upgrade)
#   - a --force restore that fails mid-way leaves the target unchanged and no staging or old
#     database behind
# Exits non-zero on any mismatch.
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
HOLDER=""

cleanup() {
  local status=$?
  if [[ -n "$HOLDER" ]]; then kill "$HOLDER" 2>/dev/null || true; fi
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

# Normalised schema (DDL incl. indexes, constraints, triggers), so lost structure fails the drill too.
# Drops comments and the per-run random \restrict token that pg_dump 18 emits.
schema_of() {
  docker exec "$1" pg_dump -U "$DB_USER" -d "$DB" --schema-only --no-owner --no-privileges \
    | grep -vE '^(--|\\(un)?restrict )' | sed '/^$/d'
}

same_as_source() { # $1 container, $2 what is being checked
  local fp schema
  fp="$(fingerprint "$1")"
  if [[ "$fp" != "$expected_fp" ]]; then
    diff <(echo "$expected_fp") <(echo "$fp") >&2 || true
    fail "$2: data differs from the source"
  fi
  schema="$(schema_of "$1")"
  if [[ "$schema" != "$source_schema" ]]; then
    diff <(echo "$source_schema") <(echo "$schema") >&2 || true
    fail "$2: schema differs from the source"
  fi
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
grep -q '^shared.spring_session|3|' <<<"$before" || fail "sample sessions not loaded as expected"
source_schema="$(schema_of "$SRC")"
# Expected after restore: everything identical, except that session rows are not backed up.
expected_fp="$(sed -E 's/^(shared\.spring_session(_attributes)?)\|[0-9]+\|[0-9a-f]+$/\1|0|d41d8cd98f00b204e9800998ecf8427e/' <<<"$before")"

step "backup with retention 2 (two older archives and one hand-named file already present)"
mkdir -p "$WORK/backups"
touch "$WORK/backups/restaurantpro-20000101T000000Z.tar" "$WORK/backups/restaurantpro-20000102T000000Z.tar" \
  "$WORK/backups/restaurantpro-before-upgrade.tar"
archive="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$WORK/backups" --keep 2)"
[[ -f "$archive" ]] || fail "backup.sh did not report an existing archive (got '$archive')"
kept="$(cd "$WORK/backups" && ls)"
expected="$(printf '%s\n' restaurantpro-20000102T000000Z.tar "$(basename "$archive")" restaurantpro-before-upgrade.tar | sort)"
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
same_as_source "$DST" "restore"
while IFS= read -r line; do echo "    $line" >&2; done <<<"$(fingerprint "$DST")"

step "append-only trigger survived the restore"
if psql_in "$DST" -c "UPDATE audit.audit_event SET action = 'x' WHERE id = 1" 2>/dev/null; then
  fail "audit trigger missing after restore"
fi

step "non-empty target is refused without --force"
# Simulate a later state (as after an upgrade): a changed row, a new table, a new schema.
psql_in "$DST" <<'SQL'
INSERT INTO organization.price_sample (store_id, amount) SELECT id, 1 FROM organization.store LIMIT 1;
CREATE TABLE organization.added_by_upgrade (id int PRIMARY KEY);
CREATE SCHEMA added_module;
CREATE TABLE added_module.thing (id int);
SQL
changed="$(fingerprint "$DST")"
if "$DEPLOY_DIR/restore.sh" --container "$DST" "$archive" 2>"$WORK/refused.err"; then fail "restore.sh overwrote a non-empty target without --force"; fi
grep -q 'is not empty' "$WORK/refused.err" || fail "not refused by the non-empty guard: $(cat "$WORK/refused.err")"
[[ "$(fingerprint "$DST")" == "$changed" ]] || fail "refused restore changed the target"

leftover_dbs() { psql_in "$1" -At -c "SELECT string_agg(datname, ' ') FROM pg_database WHERE datname LIKE '${DB}\_%'"; }

step "--force replaces the target while a client is connected; later objects are gone"
# An open session, like a running backend that was not stopped.
docker exec "$DST" psql -X -q -U "$DB_USER" -d "$DB" -c "SELECT pg_sleep(300)" >/dev/null 2>&1 &
HOLDER=$!
for _ in $(seq 1 20); do
  [[ "$(psql_in "$DST" -At -c "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'SELECT pg_sleep(300)%'")" == 1 ]] && break
  sleep 0.5
done
[[ "$(psql_in "$DST" -At -c "SELECT count(*) FROM pg_stat_activity WHERE query LIKE 'SELECT pg_sleep(300)%'")" == 1 ]] \
  || fail "could not open the holding session"
"$DEPLOY_DIR/restore.sh" --container "$DST" --force "$archive"
wait "$HOLDER" 2>/dev/null || true # its session was terminated by the swap
HOLDER=""
same_as_source "$DST" "--force restore"
[[ -z "$(leftover_dbs "$DST")" ]] || fail "--force left databases behind: $(leftover_dbs "$DST")"

step "a --force restore that fails mid-way leaves the target unchanged"
# A grant to a role that exists only in the source cluster: pg_restore fails at the ACL step,
# after all tables and data are loaded, with a checksum-valid archive.
psql_in "$SRC" -c "CREATE ROLE drill_reader; GRANT SELECT ON organization.store TO drill_reader"
mkdir "$WORK/bad"
bad_archive="$("$DEPLOY_DIR/backup.sh" --container "$SRC" --dir "$WORK/bad" --keep 1 2>/dev/null)"
if "$DEPLOY_DIR/restore.sh" --container "$DST" --force "$bad_archive" 2>"$WORK/bad.err"; then
  fail "restore.sh --force succeeded with a dump that cannot be restored"
fi
grep -q 'pg_restore failed' "$WORK/bad.err" || fail "unexpected failure: $(cat "$WORK/bad.err")"
same_as_source "$DST" "after failed --force"
[[ -z "$(leftover_dbs "$DST")" ]] || fail "failed --force left databases behind: $(leftover_dbs "$DST")"
