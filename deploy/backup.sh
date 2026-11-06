#!/usr/bin/env bash
# Back up one restaurantPRO installation (D32, spec §11).
#
# Writes one archive per run: <dir>/restaurantpro-<UTC timestamp>.tar.age, age-encrypted to every recipient
# (D98), next to its sidecar restaurantpro-<UTC timestamp>.tar.sha256 (the plaintext's SHA-256). It holds
#   db.dump          pg_dump -Fc of the database, taken inside the running postgres container
#                    (Spring Session rows excluded: restored sessions are useless and only expose data)
#   config/          compose.yaml, Caddyfile, .env (and secrets/ only when the archive is encrypted)
#   SHA256SUMS       checksums of the above, verified by restore.sh
# then deletes the oldest archives beyond --keep (only names this script writes count).
# Production (RESTAURANTPRO_ENVIRONMENT, default production) refuses, writing nothing, without a recipients
# file of at least two keys, with an age identity on the server, or with loose modes or owners (D98).
# Test mode (developer stacks): a single BACKUP_AGE_RECIPIENT is accepted, and no recipients at all writes
# a plaintext restaurantpro-<UTC timestamp>.tar with a WARNING.
#
# Usage: deploy/backup.sh [--dir DIR] [--keep N] [--container NAME] [--recipients-file FILE]
#   --dir              where archives go (default: $BACKUP_DIR or deploy/backups)
#   --keep             how many archives to keep, newest first (default: $BACKUP_KEEP or 14)
#   --container        postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --recipients-file  age recipients, one public key per line (default: $BACKUP_AGE_RECIPIENTS_FILE or
#                      deploy/backup-recipients.txt)
# Prints the archive path on stdout. Exits non-zero on any failure. Never prints credentials.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB=restaurantpro
DB_USER=restaurantpro
dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
keep="${BACKUP_KEEP:-14}"
container=""
recipients=""

die() { echo "backup: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
log() { echo "backup: $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"

while (($#)); do
  case "$1" in
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --keep) keep="${2:?--keep needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --recipients-file) recipients="${2:?--recipients-file needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || die "--keep must be a positive integer"
env_mode
recipients="${recipients:-$(recipients_file)}"
# Plaintext staging (the dump, and secrets/ when encrypting) stays outside <dir>, which is copied off the box.
work="$(mktemp -d "${TMPDIR:-/tmp}/rp-backup.XXXXXX")"
trap 'rm -rf "$work"; if [[ -n "$RP_PARTIAL" ]]; then rm -f "$RP_PARTIAL"; fi' EXIT
# D98: every check before anything is written.
if production; then
  [[ -z "${BACKUP_AGE_RECIPIENT:-}" ]] || die "BACKUP_AGE_RECIPIENT (a single key) is for test mode only; production encrypts to the --recipients-file (operational + recovery key)"
  require_safe_recipients "$recipients" 2
  refuse_identity_on_server "$dir"
  require_safe_scripts
  if [[ -e "$dir" ]]; then require_private_dir "$dir"; fi
elif [[ -n "$(recipient_keys "$recipients" 2>/dev/null)" ]]; then
  require_safe_recipients "$recipients" 1
elif [[ -n "${BACKUP_AGE_RECIPIENT:-}" ]]; then
  echo "$BACKUP_AGE_RECIPIENT" >"$work/recipient.txt"
  recipients="$work/recipient.txt"
  require_safe_recipients "$recipients" 1
else
  recipients=""
  log "WARNING: test mode without recipients: the backup is NOT encrypted (developer stacks only)"
fi
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start the stack or pass --container)"
fi

mkdir -p "$dir"
# Leftovers of killed runs: .work.* dirs of older versions of this script (may hold a plaintext dump)
# and partial copies of a final archive.
find "$dir" -maxdepth 1 -type d -name '.work.*' -mmin +60 -exec rm -rf {} +
find "$dir" -maxdepth 1 -type f -name '*.tmp.*' -mmin +60 -exec rm -f {} +
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
final="$dir/restaurantpro-$stamp.tar"
[[ ! -e "$final" && ! -e "$final.age" ]] || die "$final already exists"

log "dumping database $DB from container $container"
docker exec "$container" pg_dump -U "$DB_USER" -d "$DB" -Fc \
  --exclude-table-data=shared.spring_session --exclude-table-data=shared.spring_session_attributes >"$work/db.dump"
docker exec -i "$container" pg_restore --list >/dev/null <"$work/db.dump" || die "dump is not a readable archive"

mkdir "$work/config"
for f in compose.yaml Caddyfile .env; do
  if [[ -f "$DEPLOY_DIR/$f" ]]; then cp "$DEPLOY_DIR/$f" "$work/config/"; fi
done
if [[ -n "$recipients" && -d "$DEPLOY_DIR/secrets" ]]; then
  cp -Rp "$DEPLOY_DIR/secrets" "$work/config/secrets"
elif [[ -d "$DEPLOY_DIR/secrets" ]]; then
  log "secrets/ not included: the archive is not encrypted"
fi

(cd "$work" && find db.dump config -type f | sort | while IFS= read -r f; do sha256 "$f"; done >SHA256SUMS)
# COPYFILE_DISABLE: macOS tar would otherwise add AppleDouble ._* entries.
(cd "$work" && COPYFILE_DISABLE=1 tar -cf payload.tar SHA256SUMS db.dump config)
seal "$work/payload.tar" "$final" "$recipients" # sidecar first, then the .age, each flushed and renamed
[[ -z "$recipients" ]] || final="$final.age"
log "wrote $final ($(wc -c <"$final" | tr -d ' ') bytes)"

# Retention: UTC timestamps sort lexically, so everything before the last N names goes.
archives=()
while IFS= read -r f; do archives+=("$f"); done < <(cd "$dir" && find . -maxdepth 1 -type f \( -name 'restaurantpro-[0-9]*T[0-9]*Z.tar' -o -name 'restaurantpro-[0-9]*T[0-9]*Z.tar.age' \) | sed 's|^\./||' | sort)
for ((i = 0; i < ${#archives[@]} - keep; i++)); do
  rm -f -- "${dir:?}/${archives[i]}" "${dir:?}/${archives[i]%.tar*}.tar.sha256"
  log "retention: deleted ${archives[i]}"
done

echo "$final"
