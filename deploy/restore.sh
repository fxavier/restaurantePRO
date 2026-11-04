#!/usr/bin/env bash
# Restore a backup.sh archive into a PostgreSQL database (D32, spec §11).
#
# Steps: decrypt (.age), check SHA256SUMS, check the dump with pg_restore --list, refuse a
# target database that already holds user objects unless --force, then pg_restore in a single
# transaction (all or nothing). With --force the archive is restored into a new database which
# then replaces the target, so nothing created after the backup survives (rollback after an
# upgrade: newer schemas, tables and Flyway history rows all go). Configuration files are not touched: extract them by hand
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

work="$(mktemp -d "${TMPDIR:-/tmp}/rp-restore.XXXXXX")"
trap 'rm -rf "$work"' EXIT

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

psql_in() { local d=$1; shift; docker exec -i "$container" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$d" "$@"; }
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

staging="${db}_restore_$$"
log "--force: restoring into new database $staging, then replacing $db ($objects relations)"
psql_in postgres -c "CREATE DATABASE \"$staging\" OWNER \"$DB_USER\" TEMPLATE template0"
if ! restore_into "$staging"; then
  psql_in postgres -c "DROP DATABASE IF EXISTS \"$staging\"" || true
  die "pg_restore failed; target left unchanged"
fi
psql_in postgres -c "DROP DATABASE \"$db\" WITH (FORCE)" \
  -c "ALTER DATABASE \"$staging\" RENAME TO \"$db\"" \
  || die "could not swap databases; the restored data is in database $staging (rename it to $db by hand)"
log "done"
