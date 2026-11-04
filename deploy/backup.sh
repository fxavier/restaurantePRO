#!/usr/bin/env bash
# Back up one restaurantPRO installation (D32, spec §11).
#
# Writes one archive per run: <dir>/restaurantpro-<UTC timestamp>.tar[.age] containing
#   db.dump          pg_dump -Fc of the database, taken inside the running postgres container
#                    (Spring Session rows excluded: restored sessions are useless and only expose data)
#   config/          compose.yaml, Caddyfile, .env (and secrets/ only when the archive is encrypted)
#   SHA256SUMS       checksums of the above, verified by restore.sh
# then deletes the oldest archives beyond --keep (only names this script writes count).
#
# Usage: deploy/backup.sh [--dir DIR] [--keep N] [--container NAME] [--age-recipient RECIPIENT]
#   --dir            where archives go (default: $BACKUP_DIR or deploy/backups)
#   --keep           how many archives to keep, newest first (default: $BACKUP_KEEP or 14)
#   --container      postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --age-recipient  encrypt with age to this public key (default: $BACKUP_AGE_RECIPIENT; unset = no encryption)
# Prints the archive path on stdout. Exits non-zero on any failure. Never prints credentials.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB=restaurantpro
DB_USER=restaurantpro
dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
keep="${BACKUP_KEEP:-14}"
container=""
recipient="${BACKUP_AGE_RECIPIENT:-}"

die() { echo "backup: $*" >&2; exit 1; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
log() { echo "backup: $*" >&2; }

while (($#)); do
  case "$1" in
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --keep) keep="${2:?--keep needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --age-recipient) recipient="${2:?--age-recipient needs a value}"; shift 2 ;;
    -h | --help) sed -n '2,16p' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || die "--keep must be a positive integer"
if [[ -n "$recipient" ]]; then command -v age >/dev/null || die "age is not installed but a recipient is configured"; fi
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start the stack or pass --container)"
fi

mkdir -p "$dir"
# A run killed mid-way leaves a .work.* dir that may hold a plaintext dump: sweep old ones.
find "$dir" -maxdepth 1 -type d -name '.work.*' -mmin +60 -exec rm -rf {} +
if [[ -z "$recipient" ]]; then
  log "WARNING: backup is NOT encrypted (spec §10 asks for encrypted backups); set BACKUP_AGE_RECIPIENT"
fi
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="restaurantpro-$stamp.tar"
[[ -n "$recipient" ]] && name="$name.age"
final="$dir/$name"
[[ ! -e "$final" ]] || die "$final already exists"

work="$(mktemp -d "$dir/.work.XXXXXX")"
trap 'rm -rf "$work"' EXIT

log "dumping database $DB from container $container"
docker exec "$container" pg_dump -U "$DB_USER" -d "$DB" -Fc \
  --exclude-table-data=shared.spring_session --exclude-table-data=shared.spring_session_attributes >"$work/db.dump"
docker exec -i "$container" pg_restore --list >/dev/null <"$work/db.dump" || die "dump is not a readable archive"

mkdir "$work/config"
for f in compose.yaml Caddyfile .env; do
  if [[ -f "$DEPLOY_DIR/$f" ]]; then cp "$DEPLOY_DIR/$f" "$work/config/"; fi
done
if [[ -n "$recipient" && -d "$DEPLOY_DIR/secrets" ]]; then
  cp -Rp "$DEPLOY_DIR/secrets" "$work/config/secrets"
elif [[ -d "$DEPLOY_DIR/secrets" ]]; then
  log "secrets/ not included: archive is not encrypted (set BACKUP_AGE_RECIPIENT to include it)"
fi

(cd "$work" && find db.dump config -type f | sort | while IFS= read -r f; do sha256 "$f"; done >SHA256SUMS)
# COPYFILE_DISABLE: macOS tar would otherwise add AppleDouble ._* entries.
(cd "$work" && COPYFILE_DISABLE=1 tar -cf payload.tar SHA256SUMS db.dump config)

if [[ -n "$recipient" ]]; then
  age -r "$recipient" -o "$work/payload.out" "$work/payload.tar"
else
  mv "$work/payload.tar" "$work/payload.out"
fi
mv "$work/payload.out" "$final" # same filesystem: the archive appears complete or not at all
log "wrote $final ($(wc -c <"$final" | tr -d ' ') bytes)"

# Retention: UTC timestamps sort lexically, so everything before the last N names goes.
archives=()
while IFS= read -r f; do archives+=("$f"); done < <(cd "$dir" && find . -maxdepth 1 -type f \( -name 'restaurantpro-[0-9]*T[0-9]*Z.tar' -o -name 'restaurantpro-[0-9]*T[0-9]*Z.tar.age' \) | sed 's|^\./||' | sort)
for ((i = 0; i < ${#archives[@]} - keep; i++)); do
  rm -f -- "${dir:?}/${archives[i]}"
  log "retention: deleted ${archives[i]}"
done

echo "$final"
