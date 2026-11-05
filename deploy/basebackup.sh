#!/usr/bin/env bash
# Physical backup for point-in-time recovery (D90, spec §11: RPO 15 min). Pairs with pitr-restore.sh.
#
# PostgreSQL archives every finished WAL segment into the pg-wal-archive volume (compose.yaml,
# postgres/archive-wal.sh). This script copies that off the database volume:
#   default     a base backup (pg_basebackup -Ft -X stream, checked with pg_verifybackup), written to
#                 <dir>/base/restaurantpro-base-<UTC timestamp>-<start WAL segment>.tar[.age]
#               holding base.tar.gz, pg_wal.tar.gz, backup_manifest and SHA256SUMS; then the WAL sync
#               below; then retention: keeps the newest --keep base backups and deletes the WAL segments
#               older than the oldest kept one, both in <dir>/wal and in the pg-wal-archive volume.
#   --wal-only  only the WAL sync: copies each archived segment not yet in <dir>/wal there
#               (one <segment>[.age] file each). Run it every 5 minutes, then copy <dir> off the box.
#
# Usage: deploy/basebackup.sh [--wal-only] [--dir DIR] [--keep N] [--container NAME] [--age-recipient RECIPIENT]
#   --dir            where backups go (default: $PITR_DIR or deploy/backups/pitr)
#   --keep           base backups to keep, newest first (default: $PITR_KEEP or 7)
#   --container      postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --age-recipient  encrypt with age to this public key, as backup.sh (default: $BACKUP_AGE_RECIPIENT;
#                    unset = no encryption)
# Prints the base backup path on stdout (nothing with --wal-only). Exits non-zero on any failure.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
ARCHIVE_IN_CONTAINER=/wal-archive
dir="${PITR_DIR:-$DEPLOY_DIR/backups/pitr}"
keep="${PITR_KEEP:-7}"
container=""
recipient="${BACKUP_AGE_RECIPIENT:-}"
wal_only=false

die() { echo "basebackup: $*" >&2; exit 1; }
log() { echo "basebackup: $*" >&2; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

while (($#)); do
  case "$1" in
    --wal-only) wal_only=true; shift ;;
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --keep) keep="${2:?--keep needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --age-recipient) recipient="${2:?--age-recipient needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || die "--keep must be a positive integer"
if [[ -n "$recipient" ]]; then command -v age >/dev/null || die "age is not installed but a recipient is configured"; fi
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start the stack or pass --container)"
fi
[[ "$(docker exec "$container" psql -X -At -U "$DB_USER" -d postgres -c 'SHOW archive_mode')" == on ]] \
  || die "archive_mode is not on in $container: WAL archiving is not configured (compose.yaml)"

mkdir -p "$dir/base" "$dir/wal"
# A killed run leaves a .work.* dir that may hold plaintext data: sweep old ones.
find "$dir" -maxdepth 1 -type d -name '.work.*' -mmin +60 -exec rm -rf {} +
[[ -n "$recipient" ]] || log "WARNING: backups are NOT encrypted (spec §10 asks for encrypted backups); set BACKUP_AGE_RECIPIENT"
work="$(mktemp -d "$dir/.work.XXXXXX")"
remote="" # base backup directory inside the container, while it exists
cleanup() {
  rm -rf "$work"
  if [[ -n "$remote" ]]; then docker exec "$container" rm -rf "$remote" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# Writes $1 to $2 (age-encrypted when a recipient is set), appearing complete or not at all.
seal() {
  if [[ -n "$recipient" ]]; then
    age -r "$recipient" -o "$2.tmp" "$1"
  else
    cp "$1" "$2.tmp"
  fi
  mv "$2.tmp" "$2"
}

base_archive=""
if ! $wal_only; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  remote="/tmp/rp-basebackup-$stamp"
  log "base backup of container $container"
  docker exec "$container" pg_basebackup -U "$DB_USER" -D "$remote" -Ft -z -X stream --checkpoint=fast
  docker exec "$container" pg_verifybackup -n "$remote" >&2 || die "pg_verifybackup rejected the base backup"
  start_wal="$(docker exec "$container" sh -c "tar -xzOf '$remote/base.tar.gz' backup_label" \
    | sed -n 's/^START WAL LOCATION: .*(file \([0-9A-F]\{24\}\))$/\1/p')"
  [[ "$start_wal" =~ ^[0-9A-F]{24}$ ]] || die "cannot read the start WAL segment from backup_label"
  mkdir "$work/base"
  docker exec "$container" tar -C "$remote" -cf - . | tar -xf - -C "$work/base"
  docker exec "$container" rm -rf "$remote"
  remote=""
  (cd "$work/base" && sha256 backup_manifest base.tar.gz pg_wal.tar.gz >SHA256SUMS)
  (cd "$work/base" && COPYFILE_DISABLE=1 tar -cf ../payload.tar SHA256SUMS backup_manifest base.tar.gz pg_wal.tar.gz)
  name="restaurantpro-base-$stamp-$start_wal.tar"
  [[ -n "$recipient" ]] && name="$name.age"
  base_archive="$dir/base/$name"
  [[ ! -e "$base_archive" ]] || die "$base_archive already exists"
  seal "$work/payload.tar" "$base_archive"
  rm -rf "$work/base" "$work/payload.tar"
  log "wrote $base_archive ($(wc -c <"$base_archive" | tr -d ' ') bytes, starts at WAL $start_wal)"
fi

# WAL sync: segments, timeline history and backup history files; never the .tmp of a copy in progress.
WAL_NAME='^([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$'
new=()
while IFS= read -r f; do
  [[ "$f" =~ $WAL_NAME ]] || continue
  [[ -e "$dir/wal/$f" || -e "$dir/wal/$f.age" ]] || new+=("$f")
done < <(docker exec "$container" ls -1 "$ARCHIVE_IN_CONTAINER")
if ((${#new[@]})); then
  mkdir "$work/wal"
  docker exec "$container" tar -C "$ARCHIVE_IN_CONTAINER" -cf - "${new[@]}" | tar -xf - -C "$work/wal"
  for f in "${new[@]}"; do
    target="$dir/wal/$f"
    [[ -n "$recipient" ]] && target="$target.age"
    seal "$work/wal/$f" "$target"
  done
  rm -rf "$work/wal"
fi
log "WAL sync: ${#new[@]} new file(s) in $dir/wal"

if ! $wal_only; then
  bases=()
  while IFS= read -r f; do bases+=("$f"); done < <(cd "$dir/base" && find . -maxdepth 1 -type f \
    \( -name 'restaurantpro-base-*Z-*.tar' -o -name 'restaurantpro-base-*Z-*.tar.age' \) | sed 's|^\./||' | sort)
  for ((i = 0; i < ${#bases[@]} - keep; i++)); do
    rm -f -- "${dir:?}/base/${bases[i]}"
    log "retention: deleted base ${bases[i]}"
  done
  oldest="${bases[$((${#bases[@]} > keep ? ${#bases[@]} - keep : 0))]}"
  oldest_wal="$(sed -E 's/^restaurantpro-base-[0-9]{8}T[0-9]{6}Z-([0-9A-F]{24})\.tar(\.age)?$/\1/' <<<"$oldest")"
  [[ "$oldest_wal" =~ ^[0-9A-F]{24}$ ]] || die "cannot read the start WAL segment of $oldest"
  # Names sort in WAL order within a timeline; history files are tiny and always kept.
  older() { local seg="${1:0:24}"; [[ "$seg" =~ ^[0-9A-F]{24}$ && "$seg" < "$oldest_wal" ]]; }
  pruned=0
  while IFS= read -r f; do
    if older "$f"; then rm -f -- "$dir/wal/$f"; pruned=$((pruned + 1)); fi
  done < <(cd "$dir/wal" && ls -1)
  # Every file in the volume was synced above, so this removes nothing that exists only there.
  stale=()
  while IFS= read -r f; do if older "$f"; then stale+=("$f"); fi; done < <(docker exec "$container" ls -1 "$ARCHIVE_IN_CONTAINER")
  if ((${#stale[@]})); then docker exec "$container" sh -c 'cd "$0" && rm -f -- "$@"' "$ARCHIVE_IN_CONTAINER" "${stale[@]}"; fi
  log "retention: WAL before $oldest_wal removed ($pruned file(s) in $dir/wal, ${#stale[@]} in the pg-wal-archive volume)"
fi

[[ -z "$base_archive" ]] || echo "$base_archive"
