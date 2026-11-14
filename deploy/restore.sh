#!/usr/bin/env bash
# Restore a backup.sh archive into a PostgreSQL database (D32, spec §11).
#
# Steps: check the ciphertext against its .sha256 sidecar (required in production), decrypt (.age, through
# lib/age.sh into a private temporary directory), check the plaintext against the sidecar, check SHA256SUMS, check the dump with pg_restore --list, refuse a
# target database that already holds user objects unless --force, then provision the roles and pg_restore in a single
# transaction (all or nothing; objects owned by the superuser, privileges kept), then provision again. With --force the archive is restored into a staging database
# which then replaces the target, so nothing created after the backup survives (rollback after
# an upgrade: newer schemas, tables and Flyway history rows all go). The swap closes the
# target's connections and is made of reversible renames:
#   <db> -> <db>_old_<pid>,  <db>_restore_<pid> -> <db>,  drop <db>_old_<pid>
# so an interruption at any point leaves the data in a named database; the next run explains
# how to recover. Configuration files are not touched: extract them by hand
# (see deploy/README.md, "Restore drill").
#
# Usage: deploy/restore.sh [--force] [--container NAME] [--other-installation PROJECT] [--db NAME]
#                          [--identity AGE_KEY_FILE] [--secrets DIR] ARCHIVE
#   --force      replace a non-empty target database entirely (drops it, open connections included)
#   --container  postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --other-installation  the target container belongs to Compose project PROJECT, another installation than
#                this one (COMPOSE_PROJECT_NAME, else the env file, else compose.yaml's name). Without it such a
#                container is refused, with or without --force; with it only that exact project is accepted,
#                with a WARNING. Containers Compose does not manage (throwaway, pitr-restore.sh) are allowed
#   --db         target database (default: restaurantpro)
#   --identity   age identity file, needed for .age archives (default: $BACKUP_AGE_IDENTITY). It must be
#                yours, mode 0600 or 0400, and outside deploy/ and the archive's directory (D98 rule 2):
#                restores of encrypted backups belong on the restore host, never on the server
#   --secrets    directory with db_owner_password and db_app_password (default: deploy/secrets; required with
#                --other-installation, which must name that installation's own): the restore
#                provisions the database roles (ADR 0039, D163) with deploy/postgres/provision.sh before pg_restore
#                (the dump grants to restaurantpro_app) and after it (the restored objects, owned by the superuser,
#                go to restaurantpro_owner). On a scratch container, any throwaway pair will do
# Exits non-zero on any failure; on failure before the swap the target is left as it was.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
db=restaurantpro
force=false
other_installation=""
container=""
identity="${BACKUP_AGE_IDENTITY:-}"
secrets="$DEPLOY_DIR/secrets"
secrets_given=false
archive=""

die() { echo "restore: $*" >&2; exit 1; }
log() { echo "restore: $*" >&2; }
sha256_check() { if command -v sha256sum >/dev/null; then sha256sum -c --quiet "$@"; else shasum -a 256 -c --quiet "$@"; fi; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=lib/roles.sh
. "$DEPLOY_DIR/lib/roles.sh"

while (($#)); do
  case "$1" in
    --force) force=true; shift ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --other-installation) other_installation="${2:?--other-installation needs a value}"; shift 2 ;;
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a value}"; shift 2 ;;
    --secrets) secrets="${2:?--secrets needs a value}"; secrets_given=true; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [[ -z "$archive" ]] || die "only one archive allowed"; archive="$1"; shift ;;
  esac
done
[[ -n "$archive" ]] || die "usage: restore.sh [--force] [--container NAME] [--other-installation PROJECT] [--db NAME] [--identity FILE] [--secrets DIR] ARCHIVE"
[[ -f "$archive" ]] || die "archive not found: $archive"
env_mode
sidecar="${archive%.age}"
sidecar="${sidecar%.tar}.tar.sha256"
if [[ "$archive" == *.age ]]; then
  [[ -n "$identity" ]] || die "encrypted archive: pass --identity or set BACKUP_AGE_IDENTITY"
  require_identity "$identity" "$(dirname "$archive")"
  if [[ -e "$sidecar" ]]; then
    is_sidecar "$sidecar" || die "$sidecar is not a well-formed sidecar"
    # Key-free first: damaged or swapped ciphertext is caught before anything is decrypted.
    [[ "$(sha256_hex "$archive")" == "$(sidecar_cipher "$sidecar")" ]] || die "checksum mismatch: $archive does not match the ciphertext SHA-256 in $sidecar"
  elif production; then
    die "no sidecar $sidecar next to $archive: copy it too (production restores check it)"
  fi
fi
# The restore sets the role passwords of the target's cluster: this installation's own secrets would hand them to the
# other installation (its backend could no longer log in, and this installation's passwords would open it).
if [[ -n "$other_installation" ]] && ! $secrets_given; then
  die "--other-installation needs --secrets with $other_installation's own db_owner_password and db_app_password: the restore sets the role passwords of the target cluster"
fi
for f in db_owner_password db_app_password; do
  [[ -s "$secrets/$f" ]] || die "role secret $secrets/$f not found or empty: the restore provisions the roles with it (deploy/README.md, \"Database roles\"; --secrets)"
done
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start it or pass --container)"
fi
require_own_installation "$container" "$other_installation" # before anything reads or writes the target

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
if [[ "$(psql_in postgres -c "SELECT datallowconn FROM pg_database WHERE datname = '$db'")" == f ]]; then
  staged="$(psql_in postgres -c "SELECT string_agg(datname, ' ') FROM pg_database WHERE datname LIKE '${db}\_restore\_%'")"
  die "database $db refuses connections: an earlier --force was killed during the swap, before renaming it.
  Its data is intact; reopen it with: docker exec $container psql -U $DB_USER -d postgres -c 'ALTER DATABASE \"$db\" ALLOW_CONNECTIONS true'
  ${staged:+then drop the unfinished staging copies ($staged), each with ... -c 'DROP DATABASE \"<name>\"'
  }then run the restore again."
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
  age_decrypt "$identity" <"$archive" >"$work/payload.tar" || die "cannot decrypt $archive (wrong key?)"
  if [[ -f "$sidecar" ]]; then
    [[ "$(sha256_hex "$work/payload.tar")" == "$(sidecar_plain "$sidecar")" ]] || die "checksum mismatch against $sidecar: archive is corrupted"
  fi
else
  cp "$archive" "$work/payload.tar"
fi

log "verifying $archive"
tar -xf "$work/payload.tar" -C "$work" SHA256SUMS db.dump || die "not a backup.sh archive"
tar -xf "$work/payload.tar" -C "$work" config 2>/dev/null || true
(cd "$work" && sha256_check SHA256SUMS >&2) || die "checksum mismatch: archive is corrupted"
docker exec -i "$container" pg_restore --list >/dev/null <"$work/db.dump" || die "dump is not a readable archive"

provision_roles() { # $1 database: ADR 0039 (D163), with this installation's role secrets
  "$DEPLOY_DIR/postgres/provision.sh" --container "$container" --db "$1" --secrets "$secrets"
}
# The roles first: the dump of a provisioned installation grants to restaurantpro_app, and its privileges are restored
# (a console function's EXECUTE revoked from PUBLIC must stay revoked). Objects are restored owned by the superuser
# (--no-owner), so the provisioning after hands them to restaurantpro_owner and grants the rest.
restore_into() {
  provision_roles "$1" || return 1
  docker exec -i "$container" pg_restore -U "$DB_USER" -d "$1" --no-owner --exit-on-error --single-transaction <"$work/db.dump" \
    || return 1
  local problems
  if provision_roles "$1" && problems="$(roles_problems "$container" "$1")" && [[ -z "$problems" ]]; then return 0; fi
  [[ -z "${problems:-}" ]] || log "the restored database's roles are not as D163: $(head -5 <<<"$problems" | tr '\n' ';')"
  [[ -n "$staging" ]] && die "provisioning or the role check after pg_restore failed (see above); target left unchanged"
  die "restored into $1, but provisioning or the role check failed (see above): the backend refuses to start until
  $DEPLOY_DIR/postgres/provision.sh --container $container --db $1 succeeds and the roles are as D163"
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
