#!/usr/bin/env bash
# Point-in-time recovery drill (PRD S27, acceptance A33; D90).
#
# Starts the postgres service of deploy/compose.yaml itself (throwaway project, secrets and volumes),
# so the WAL settings under test are the ones that ship. Then:
#   1. checks wal_level / archive_mode / archive_timeout on the running server
#   2. loads deploy/test/sample.sql and writes W1
#   3. takes a base backup with basebackup.sh
#   4. writes W2, notes the target time T and a fingerprint of every table, writes W3
#   5. archives the segment holding W3 and copies the WAL archive with basebackup.sh --wal-only
#   6. pitr-restore.sh --target-time T from the copied WAL into an isolated container: W1 and W2
#      present, W3 absent, every table equal to the fingerprint at T, no network, writable; then the
#      README procedure: backup.sh from the restored container, restore.sh into a fresh database
#   7. a second run on the same --name is refused without --force, and changes nothing
#   8. a target time beyond the archived WAL fails, and leaves no container or volume behind
#   9. RPO: writes W4 and waits, without forcing a switch, until archive_timeout archives it
#      (skipped with --quick, which forces the switch instead), then deletes the database
#      container and volume, keeps only the pg-wal-archive volume, and restores --force to
#      "latest" from it: W1-W4 present. Prints the measured RPO and the restore time (RTO).
#  10. a recreated database volume (a new cluster, WAL names starting again) next to the old archive:
#      basebackup.sh refuses and changes nothing; the new cluster's archiving blocks on a name clash;
#      basebackup.sh --new-cluster moves the old files aside, archiving resumes, and a restore to a
#      time T2 of the new cluster works
# Also: base backup names carry the cluster's system identifier, no plaintext staging or partial
# files are left in the backup directory, --wal-only skips while another run holds the lock and
# clears a stale lock, and --force never replaces an object pitr-restore.sh did not create.
# Exits non-zero on any mismatch.
#
# Usage: deploy/test-pitr.sh [--quick]      (needs only Docker; leaves nothing behind;
#                                           about 7 minutes, or about 2 with --quick)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB=restaurantpro
DB_USER=restaurantpro
ARCHIVE_TIMEOUT=300 # compose.yaml
quick=false
case "${1:-}" in
  --quick) quick=true ;;
  "") ;;
  *) echo "usage: test-pitr.sh [--quick]" >&2; exit 2 ;;
esac

RUN_ID="rp-pitr-drill-$$-$(date +%s)"
PITR_NAME="$RUN_ID-restored"
OTHER="$RUN_ID-other" # a name whose volume pitr-restore.sh did not create
LIVE="$RUN_ID-live" # stands in for the live database the README procedure restores into
PG_IMAGE="postgres:18.6-alpine" # D4: same pin as compose.yaml
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-pitr-drill.XXXXXX")"
HOLDER=""

compose() { docker compose -p "$RUN_ID" -f "$DEPLOY_DIR/compose.yaml" -f "$WORK/drill.override.yaml" "$@"; }

cleanup() {
  local status=$?
  if [[ -n "$HOLDER" ]]; then kill "$HOLDER" 2>/dev/null || true; fi
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  docker rm -f -v "$PITR_NAME" "$LIVE" "$OTHER" >/dev/null 2>&1 || true
  docker volume rm "$PITR_NAME-data" "$OTHER-data" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: point-in-time recovery drill" >&2; else echo "FAIL: point-in-time recovery drill (exit $status)" >&2; fi
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok: $*" >&2; }
step() { echo "--- $*" >&2; }

# Throwaway secrets and the variables compose.yaml requires; the backend and Caddy are never started.
mkdir "$WORK/secrets"
openssl rand -hex 16 >"$WORK/secrets/db_password"
openssl rand -hex 16 >"$WORK/secrets/admin_initial_password"
chmod 644 "$WORK/secrets/"*
cat >"$WORK/drill.override.yaml" <<YAML
secrets:
  db_password:
    file: $WORK/secrets/db_password
  admin_initial_password:
    file: $WORK/secrets/admin_initial_password
YAML
export RESTAURANTPRO_HOSTNAME=drill.invalid RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME=drill \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID=0 RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY=XX \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY=XXX RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME=drill

psql_in() { docker exec -i "$1" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "${@:2}"; }
q() { psql_in "$1" -At -c "$2"; }

# One line per user table ("schema.table|rows|md5 of all rows in a stable order"), as test-backup-restore.sh.
fingerprint() {
  psql_in "$1" -At <<'SQL'
SELECT format('SELECT %L, count(*), md5(coalesce(string_agg(t::text, %L ORDER BY t::text), %L)) FROM %I.%I t',
              n.nspname || '.' || c.relname, E'\n', '', n.nspname, c.relname)
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY n.nspname, c.relname
\gexec
SQL
}

write() { # $1 container, $2 id, $3 label; prints the commit time
  q "$1" "INSERT INTO drill.pitr_event (id, label) VALUES ($2, '$3')" >/dev/null
  q "$1" "SELECT to_char(committed_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"') FROM drill.pitr_event WHERE id = $2"
}

rows() { q "$1" "SELECT coalesce(string_agg(id::text, ',' ORDER BY id), '') FROM drill.pitr_event"; }

archived() { # $1 container, $2 WAL file name: true once PostgreSQL has archived it
  [[ "$(q "$1" "SELECT coalesce(last_archived_wal >= '$2', false) FROM pg_stat_archiver")" == t ]]
}

wait_archived() { # $1 container, $2 WAL file, $3 timeout seconds
  local start=$SECONDS
  until archived "$1" "$2"; do
    ((SECONDS - start < $3)) || fail "$2 not archived within $3s ($(q "$1" 'SELECT failed_count, last_failed_wal FROM pg_stat_archiver'))"
    sleep 1
  done
}

start_service() { # starts the compose postgres service and sets SRC
  compose up -d --no-build postgres >/dev/null 2>&1 || fail "compose up failed: $(compose up -d --no-build postgres 2>&1 | tail -5)"
  SRC="$(compose ps -q postgres)"
  [[ -n "$SRC" ]] || fail "postgres container not found"
  for _ in $(seq 1 60); do
    docker exec "$SRC" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" && return 0
    sleep 1
  done
  fail "postgres did not become ready"
}
sysid_of() { q "$1" 'SELECT system_identifier FROM pg_control_system()'; }
no_staging_left() { # no plaintext work dirs or partial copies in the backup directory
  local left
  left="$(find "$WORK/pitr" \( -name '.work.*' -o -name '*.tmp*' \) -print)"
  [[ -z "$left" ]] || fail "left in the backup directory: $left"
}

step "starting the postgres service of compose.yaml as project $RUN_ID"
SRC=""
start_service

step "1. WAL archiving settings"
[[ "$(q "$SRC" 'SHOW wal_level')" == replica ]] || fail "wal_level is $(q "$SRC" 'SHOW wal_level'), expected replica"
[[ "$(q "$SRC" 'SHOW archive_mode')" == on ]] || fail "archive_mode is not on"
[[ "$(q "$SRC" "SELECT setting FROM pg_settings WHERE name = 'archive_timeout'")" == "$ARCHIVE_TIMEOUT" ]] \
  || fail "archive_timeout is not ${ARCHIVE_TIMEOUT}s"
ok "wal_level=replica, archive_mode=on, archive_timeout=${ARCHIVE_TIMEOUT}s, archive_command=$(q "$SRC" 'SHOW archive_command')"

step "2. sample data and W1"
psql_in "$SRC" <"$DEPLOY_DIR/test/sample.sql"
q "$SRC" "CREATE SCHEMA drill; CREATE TABLE drill.pitr_event (id int PRIMARY KEY, label text NOT NULL,
          committed_at timestamptz NOT NULL DEFAULT clock_timestamp())" >/dev/null
w1="$(write "$SRC" 1 'W1 before the base backup')"
ok "W1 committed at $w1"

step "3. base backup"
base="$("$DEPLOY_DIR/basebackup.sh" --container "$SRC" --dir "$WORK/pitr" --keep 2)"
[[ -f "$base" ]] || fail "basebackup.sh did not report an existing archive (got '$base')"
sysid="$(sysid_of "$SRC")"
[[ "$(basename "$base")" == restaurantpro-base-*Z-"$sysid"-[0-9A-F]*.tar ]] || fail "base backup name lacks the system identifier $sysid"
[[ "$(cat "$WORK/pitr/SYSTEM_IDENTIFIER")" == "$sysid" ]] || fail "backup directory not marked with cluster $sysid"
[[ "$(docker exec "$SRC" cat /wal-archive/SYSTEM_IDENTIFIER)" == "$sysid" ]] || fail "WAL archive not marked with cluster $sysid"
no_staging_left
ok "base backup $(basename "$base"); archive and backup directory marked with cluster $sysid; no staging left"

step "4. W2, target time T, W3"
w2="$(write "$SRC" 2 'W2 after the base backup, before T')"
at_t="$(fingerprint "$SRC")"
target="$(q "$SRC" "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")"
w3="$(write "$SRC" 3 'W3 after T')"
[[ "$w2" < "$target" && "$target" < "$w3" ]] || fail "T is not between W2 and W3 ($w2 / $target / $w3)"
ok "W2 at $w2 < T = $target < W3 at $w3"

step "5. archive the segment holding W3 and copy the archive"
w3_wal="$(q "$SRC" 'SELECT pg_walfile_name(pg_current_wal_insert_lsn())')"
q "$SRC" 'SELECT pg_switch_wal()' >/dev/null
wait_archived "$SRC" "$w3_wal" 60
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$WORK/pitr" 2>/dev/null
[[ -f "$WORK/pitr/wal/$w3_wal" ]] || fail "basebackup.sh --wal-only did not copy $w3_wal"
no_staging_left
ok "$w3_wal archived to the pg-wal-archive volume and copied to $(basename "$WORK")/pitr/wal"

step "5b. one run at a time per backup directory"
sleep 600 &
HOLDER=$!
mkdir "$WORK/pitr/.lock"
echo "$HOLDER" >"$WORK/pitr/.lock/pid"
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$WORK/pitr" 2>"$WORK/locked.err" || fail "--wal-only failed while locked"
grep -q 'skipping' "$WORK/locked.err" || fail "--wal-only did not skip while locked: $(cat "$WORK/locked.err")"
[[ "$(cat "$WORK/pitr/.lock/pid")" == "$HOLDER" ]] || fail "--wal-only took a lock held by a live process"
kill "$HOLDER"
wait "$HOLDER" 2>/dev/null || true
HOLDER=""
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$WORK/pitr" 2>"$WORK/stale.err"
grep -q 'stale lock' "$WORK/stale.err" || fail "stale lock not cleared: $(cat "$WORK/stale.err")"
[[ ! -e "$WORK/pitr/.lock" ]] || fail "lock left behind"
ok "--wal-only skips while a live run holds the lock, and clears the lock of a dead one"

step "6. restore to T from the copied WAL into an isolated container"
t0=$SECONDS
restored="$("$DEPLOY_DIR/pitr-restore.sh" --target-time "$target" --name "$PITR_NAME" --wal-dir "$WORK/pitr/wal" "$base")"
rto=$((SECONDS - t0))
[[ "$restored" == "$PITR_NAME" ]] || fail "pitr-restore.sh printed '$restored'"
[[ "$(rows "$PITR_NAME")" == "1,2" ]] || fail "rows after restore to T: $(rows "$PITR_NAME"), expected 1,2"
ok "W1 and W2 present, W3 absent (rows: $(rows "$PITR_NAME"))"
if [[ "$(fingerprint "$PITR_NAME")" != "$at_t" ]]; then
  diff <(echo "$at_t") <(fingerprint "$PITR_NAME") >&2 || true
  fail "restored data differs from the data at T"
fi
ok "all $(wc -l <<<"$at_t" | tr -d ' ') tables equal the fingerprint taken at T"
[[ "$(docker container inspect -f '{{.HostConfig.NetworkMode}}' "$PITR_NAME")" == none ]] || fail "restored container has a network"
[[ "$(q "$PITR_NAME" 'SELECT pg_is_in_recovery()')" == f ]] || fail "restored server is still in recovery"
q "$PITR_NAME" "SHOW restore_command" | grep -q . && fail "restore_command left in the restored configuration"
q "$PITR_NAME" "CREATE TEMP TABLE drill_probe (i int); INSERT INTO drill_probe VALUES (1)" >/dev/null \
  || fail "restored server does not accept writes"
ok "isolated (network none), promoted, writable, recovery settings removed; restore took ${rto}s"

step "6b. bring the restored data into a database with backup.sh + restore.sh (README procedure)"
export_archive="$("$DEPLOY_DIR/backup.sh" --container "$PITR_NAME" --dir "$WORK/export" --keep 1 2>/dev/null)"
POSTGRES_PASSWORD="$(openssl rand -hex 16)" docker run -d --name "$LIVE" -e POSTGRES_PASSWORD \
  -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" "$PG_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$LIVE" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" && break
  sleep 1
done
"$DEPLOY_DIR/restore.sh" --container "$LIVE" "$export_archive" 2>/dev/null
[[ "$(rows "$LIVE")" == "1,2" ]] || fail "rows after backup.sh + restore.sh of the restored data: $(rows "$LIVE")"
ok "exported with backup.sh and loaded with restore.sh: rows $(rows "$LIVE")"
docker rm -f -v "$LIVE" >/dev/null

step "7. a second restore on the same name is refused without --force"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-dir "$WORK/pitr/wal" "$base" 2>"$WORK/refused.err" >/dev/null; then
  fail "pitr-restore.sh replaced an existing restore without --force"
fi
grep -q 'already exists' "$WORK/refused.err" || fail "not refused by the existing-target guard: $(cat "$WORK/refused.err")"
[[ "$(rows "$PITR_NAME")" == "1,2" ]] || fail "refused restore changed the existing one"
ok "refused ($(head -1 "$WORK/refused.err")); existing restore unchanged"
docker volume create "$OTHER-data" >/dev/null # same naming, but not made by pitr-restore.sh
if "$DEPLOY_DIR/pitr-restore.sh" --force --target-time latest --name "$OTHER" --wal-dir "$WORK/pitr/wal" "$base" 2>"$WORK/foreign.err" >/dev/null; then
  fail "pitr-restore.sh --force replaced a volume it did not create"
fi
grep -q 'was not created by pitr-restore.sh' "$WORK/foreign.err" || fail "unexpected: $(cat "$WORK/foreign.err")"
docker volume rm "$OTHER-data" >/dev/null || fail "the foreign volume is gone"
src_name="$(docker container inspect -f '{{.Name}}' "$SRC" | sed 's|^/||')"
if "$DEPLOY_DIR/pitr-restore.sh" --force --target-time latest --name "$src_name" --wal-dir "$WORK/pitr/wal" "$base" 2>"$WORK/live.err" >/dev/null; then
  fail "pitr-restore.sh --force replaced the compose postgres container"
fi
grep -q 'belongs to a Docker Compose project' "$WORK/live.err" || fail "unexpected: $(cat "$WORK/live.err")"
[[ "$(docker container inspect -f '{{.State.Running}}' "$SRC")" == true ]] || fail "the compose postgres container was touched"
ok "--force refused for a volume it did not create and for the compose postgres container ($src_name)"

step "8. a target time beyond the archived WAL fails and leaves nothing behind"
docker rm -f -v "$PITR_NAME" >/dev/null
docker volume rm "$PITR_NAME-data" >/dev/null
future="$(date -u -v+1d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+1 day' +%Y-%m-%dT%H:%M:%SZ)"
if "$DEPLOY_DIR/pitr-restore.sh" --target-time "$future" --name "$PITR_NAME" --wal-dir "$WORK/pitr/wal" "$base" 2>"$WORK/future.err" >/dev/null; then
  fail "pitr-restore.sh succeeded with a target the WAL does not reach"
fi
grep -q 'recovery ended before configured recovery target was reached' "$WORK/future.err" \
  || fail "unexpected failure: $(tail -5 "$WORK/future.err")"
docker container inspect "$PITR_NAME" >/dev/null 2>&1 && fail "failed restore left container $PITR_NAME"
docker volume inspect "$PITR_NAME-data" >/dev/null 2>&1 && fail "failed restore left volume $PITR_NAME-data"
ok "refused by PostgreSQL (recovery target not reached); no container or volume left"

step "9. RPO: W4 archived by archive_timeout, then the database is lost and restored from the WAL volume"
w4="$(write "$SRC" 4 'W4 last write before the loss')"
w4_wal="$(q "$SRC" 'SELECT pg_walfile_name(pg_current_wal_insert_lsn())')"
if $quick; then
  q "$SRC" 'SELECT pg_switch_wal()' >/dev/null
  wait_archived "$SRC" "$w4_wal" 60
  rpo_note="not measured (--quick forced the switch); bound: archive_timeout=${ARCHIVE_TIMEOUT}s"
else
  echo "    waiting up to $((ARCHIVE_TIMEOUT + 60))s for archive_timeout to archive $w4_wal (no forced switch)" >&2
  wait_archived "$SRC" "$w4_wal" $((ARCHIVE_TIMEOUT + 60))
  rpo="$(q "$SRC" "SELECT round(extract(epoch FROM last_archived_time - '$w4'::timestamptz)) FROM pg_stat_archiver")"
  ((rpo <= ARCHIVE_TIMEOUT + 5)) || fail "W4 took ${rpo}s to be archived, more than archive_timeout=${ARCHIVE_TIMEOUT}s"
  rpo_note="${rpo}s from W4's commit to its segment in the archive (bound: archive_timeout=${ARCHIVE_TIMEOUT}s)"
fi
compose rm -s -f postgres >/dev/null 2>&1
docker volume rm "${RUN_ID}_db-data" >/dev/null || fail "could not delete the database volume"
t0=$SECONDS
"$DEPLOY_DIR/pitr-restore.sh" --target-time latest --name "$PITR_NAME" --wal-volume "${RUN_ID}_pg-wal-archive" "$base" >/dev/null
rto=$((SECONDS - t0))
[[ "$(rows "$PITR_NAME")" == "1,2,3,4" ]] || fail "rows after restore to latest: $(rows "$PITR_NAME"), expected 1,2,3,4"
ok "database container and volume deleted; restore to latest from the pg-wal-archive volume has W1-W4"
ok "RPO measured: $rpo_note"
ok "RTO measured: ${rto}s for pitr-restore.sh (base backup + WAL replay, this drill's data size)"

step "10. a recreated database volume next to the old archive"
compose up -d --no-build postgres >/dev/null 2>&1 # db-data was deleted in step 9: a brand-new cluster
start_service
new_sysid="$(sysid_of "$SRC")"
[[ "$new_sysid" != "$sysid" ]] || fail "expected a new cluster"
if "$DEPLOY_DIR/basebackup.sh" --container "$SRC" --dir "$WORK/pitr" 2>"$WORK/other.err" >/dev/null; then
  fail "basebackup.sh ran against the archive of another cluster"
fi
grep -q "belongs to database cluster $sysid, but .* runs cluster $new_sysid" "$WORK/other.err" \
  || fail "unexpected: $(cat "$WORK/other.err")"
[[ -f "$base" && "$(cat "$WORK/pitr/SYSTEM_IDENTIFIER")" == "$sysid" ]] || fail "the refused run changed the old backups"
ok "refused: $(head -1 "$WORK/other.err" | sed 's/^basebackup: //'); nothing changed"
clash=""
for i in $(seq 1 15); do # the new cluster's WAL names start again and soon hit an old cluster's file
  q "$SRC" "CREATE TABLE IF NOT EXISTS clash_probe (i int); INSERT INTO clash_probe VALUES ($i)" >/dev/null
  q "$SRC" 'SELECT pg_switch_wal()' >/dev/null
  clash="$(q "$SRC" 'SELECT coalesce(last_failed_wal, $$$$) FROM pg_stat_archiver')"
  [[ -n "$clash" ]] && break
  sleep 1
done
[[ -n "$clash" ]] || fail "expected the new cluster's archiving to clash with the old files"
ok "the new cluster cannot archive $clash: the old cluster's file of that name is in the archive"
new_base="$("$DEPLOY_DIR/basebackup.sh" --new-cluster --container "$SRC" --dir "$WORK/pitr" 2>"$WORK/new.err")" \
  || fail "basebackup.sh --new-cluster failed: $(cat "$WORK/new.err")"
compgen -G "$WORK/pitr/old-$sysid-*/base/*.tar" >/dev/null || fail "old base backups not moved aside"
[[ -n "$(docker exec "$SRC" sh -c "ls -d /wal-archive/old-$sysid-*")" ]] || fail "old WAL not moved aside in the volume"
[[ "$(cat "$WORK/pitr/SYSTEM_IDENTIFIER")" == "$new_sysid" ]] || fail "backup directory not re-marked"
wait_archived "$SRC" "$clash" 120
ok "--new-cluster moved the old files aside, $clash is now archived, new base $(basename "$new_base")"
q "$SRC" "CREATE SCHEMA drill; CREATE TABLE drill.pitr_event (id int PRIMARY KEY, label text NOT NULL,
          committed_at timestamptz NOT NULL DEFAULT clock_timestamp())" >/dev/null
n1="$(write "$SRC" 11 'N1 new cluster, before T2')"
target2="$(q "$SRC" "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"')")"
n2="$(write "$SRC" 12 'N2 new cluster, after T2')"
n2_wal="$(q "$SRC" 'SELECT pg_walfile_name(pg_current_wal_insert_lsn())')"
q "$SRC" 'SELECT pg_switch_wal()' >/dev/null
wait_archived "$SRC" "$n2_wal" 60
"$DEPLOY_DIR/basebackup.sh" --wal-only --container "$SRC" --dir "$WORK/pitr" 2>/dev/null
"$DEPLOY_DIR/pitr-restore.sh" --force --target-time "$target2" --name "$PITR_NAME" --wal-dir "$WORK/pitr/wal" "$new_base" >/dev/null 2>&1 \
  || fail "restore of the new cluster to T2 failed"
[[ "$(rows "$PITR_NAME")" == "11" ]] || fail "rows after restore to T2: $(rows "$PITR_NAME"), expected 11"
no_staging_left
ok "new cluster: N1 at $n1 < T2 = $target2 < N2 at $n2; restore to T2 has N1 and not N2"
