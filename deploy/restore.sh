#!/usr/bin/env bash
# Restore a backup.sh archive into a PostgreSQL database (D32, spec §11).
#
# Steps: decrypt (.age), check SHA256SUMS, check the dump with pg_restore --list, refuse a
# target database that already holds user objects unless --force, then pg_restore in a single
# transaction (all or nothing). With --force the archive is restored into a staging database
# which then replaces the target, so nothing created after the backup survives (rollback after
# an upgrade: newer schemas, tables and Flyway history rows all go). The swap closes the
# target's connections and is made of reversible renames:
#   <db> -> <db>_old_<pid>,  <db>_restore_<pid> -> <db>,  drop <db>_old_<pid>
# so an interruption at any point leaves the data in a named database; the next run explains
# how to recover. Configuration files are not touched: extract them by hand
# (see deploy/README.md, "Restore drill").
#
# Usage: deploy/restore.sh [--force] [--container NAME] [--db NAME] [--identity AGE_KEY_FILE] ARCHIVE
#   --force      replace a non-empty target database entirely (drops it, open connections included)
#   --container  postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --db         target database (default: restaurantpro)
#   --identity   age identity file, needed for .age archives (default: $BACKUP_AGE_IDENTITY)
# Exits non-zero on any failure; on failure the target is left as it was.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
db=restaurantpro
force=false
container=""
identity="${BACKUP_AGE_IDENTITY:-}"
archive=""

die() { echo "restore: $*" >&2; exit 1; }
log() { echo "restore: $*" >&2; }
sha256_check() { if command -v sha256sum >/dev/null; then sha256sum -c --quiet "$@"; else shasum -a 256 -c --quiet "$@"; fi; }

while (($#)); do
  case "$1" in
    --force) force=true; shift ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,14p' "$0" >&2; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [[ -z "$archive" ]] || die "only one archive allowed"; archive="$1"; shift ;;
  esac
done
[[ -n "$archive" ]] || die "usage: restore.sh [--force] [--container NAME] [--db NAME] [--identity FILE] ARCHIVE"
[[ -f "$archive" ]] || die "archive not found: $archive"
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start it or pass --container)"
fi

psql_in() { local d=$1; shift; docker exec -i "$container" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$d" "$@"; }
admin() { psql_in postgres -c "$1"; }

if [[ "$(psql_in postgres -c "SELECT count(*) FROM pg_database WHERE datname = '$db'")" == 0 ]]; then
  leftovers="$(psql_in postgres -c "SELECT string_agg(datname, ' ') FROM pg_database
    WHERE datname LIKE '${db}\_restore\_%' OR datname LIKE '${db}\_old\_%'")"
  [[ -n "$leftovers" ]] || die "database $db does not exist (create it empty: createdb -O $DB_USER $db)"
  die "database $db does not exist, but an interrupted --force left: $leftovers
  ${db}_old_<pid> is the database as it was before that restore; ${db}_restore_<pid> is the restored copy.
  Pick one and rename it, e.g.: docker exec $container psql -U $DB_USER -d postgres -c 'ALTER DATABASE \"<name>\" RENAME TO \"$db\"'
  and, for ${db}_old_<pid>, also ... -c 'ALTER DATABASE \"$db\" ALLOW_CONNECTIONS true'
  then drop the other one."
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/rp-restore.XXXXXX")"
staging="" # set while a staging database exists that nothing else refers to
reopen=""  # set while the target refuses connections but has not been moved aside yet
cleanup() {
  rm -rf "$work"
  if [[ -n "$reopen" ]]; then admin "ALTER DATABASE \"$reopen\" ALLOW_CONNECTIONS true" >/dev/null 2>&1 || true; fi
  if [[ -n "$staging" ]]; then admin "DROP DATABASE IF EXISTS \"$staging\" WITH (FORCE)" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

if [[ "$archive" == *.age ]]; then
  command -v age >/dev/null || die "age is not installed"
  [[ -n "$identity" ]] || die "encrypted archive: pass --identity or set BACKUP_AGE_IDENTITY"
  age -d -i "$identity" -o "$work/payload.tar" "$archive" || die "cannot decrypt $archive"
else
  cp "$archive" "$work/payload.tar"
fi

log "verifying $archive"
tar -xf "$work/payload.tar" -C "$work" SHA256SUMS db.dump || die "not a backup.sh archive"
tar -xf "$work/payload.tar" -C "$work" config 2>/dev/null || true
(cd "$work" && sha256_check SHA256SUMS >&2) || die "checksum mismatch: archive is corrupted"
docker exec -i "$container" pg_restore --list >/dev/null <"$work/db.dump" || die "dump is not a readable archive"

restore_into() {
  docker exec -i "$container" pg_restore -U "$DB_USER" -d "$1" --no-owner --exit-on-error --single-transaction <"$work/db.dump"
}

objects="$(psql_in "$db" -c "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'")"
if [[ "$objects" == 0 ]]; then
  log "restoring into database $db in container $container"
  restore_into "$db" || die "pg_restore failed; target left unchanged"
  log "done"
  exit 0
fi
$force || die "target database $db is not empty ($objects relations); use --force to replace it"

new="${db}_restore_$$"
old="${db}_old_$$"
log "--force: restoring into new database $new, then replacing $db ($objects relations)"
staging="$new"
admin "CREATE DATABASE \"$new\" OWNER \"$DB_USER\" TEMPLATE template0"
restore_into "$new" || die "pg_restore failed; target left unchanged"

# Swap. Block new connections to the target, close the open ones, move it aside.
reopen="$db"
admin "ALTER DATABASE \"$db\" ALLOW_CONNECTIONS false"
moved=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
  admin "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid()" >/dev/null
  if admin "ALTER DATABASE \"$db\" RENAME TO \"$old\"" 2>/dev/null; then moved=true; break; fi
  sleep 1 # terminated backends take a moment to exit
done
$moved || die "could not close the connections to $db; target left unchanged (stop the backend and retry)"
reopen=""
staging="" # from here on the restored copy is the only good candidate: keep it whatever happens
admin "ALTER DATABASE \"$new\" RENAME TO \"$db\"" \
  || die "swap interrupted: the old data is in $old, the restored data in $new; rename one of them to $db"
admin "DROP DATABASE \"$old\"" || die "restored, but the previous database $old could not be dropped; drop it by hand"
log "done"
