#!/usr/bin/env bash
# Physical backup for point-in-time recovery (D90, spec §11: RPO 15 min). Pairs with pitr-restore.sh.
#
# PostgreSQL archives every finished WAL segment into the pg-wal-archive volume (compose.yaml,
# postgres/archive-wal.sh). This script copies that off the database volume:
#   default        a base backup (pg_basebackup -Ft -X stream, checked with pg_verifybackup), written to
#                    <dir>/base/restaurantpro-base-<UTC timestamp>-<system id>-<start WAL segment>.tar[.age]
#                  holding base.tar.gz, pg_wal.tar.gz, backup_manifest and SHA256SUMS; then the WAL sync
#                  below; then retention: keeps the newest --keep base backups and deletes the WAL older
#                  than the oldest kept one, both in <dir>/wal and in the pg-wal-archive volume.
#   --wal-only     only the WAL sync: copies each archived file not yet in <dir>/wal there
#                  (one <segment>[.age] file each). Run it every 5 minutes, then copy <dir> off the box.
#                  Skips (exit 0) while another run holds the lock: that run syncs the WAL too.
#   --new-cluster  after the database volume was recreated (a new cluster, whose WAL names start again
#                  from the beginning): moves the old cluster's files aside, in the volume to
#                  /wal-archive/old-<old system id>-<timestamp>/ and in <dir> to <dir>/old-<...>/, then
#                  runs as default. Without it, any run refuses an archive or <dir> of another cluster.
# The archive and <dir> each hold a SYSTEM_IDENTIFIER file naming the cluster they belong to (the
# system_identifier of pg_control_system()); a run against a different cluster stops with an error
# and touches nothing. A run also fails, after doing its work, while PostgreSQL's archiving is failing.
#
# Usage: deploy/basebackup.sh [--wal-only | --new-cluster] [--dir DIR] [--keep N] [--container NAME]
#                             [--age-recipient RECIPIENT]
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
MARKER=SYSTEM_IDENTIFIER
LOCK_WAIT=3600 # seconds a base backup waits for a running --wal-only (or other) run
WAL_NAME='^([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$'
dir="${PITR_DIR:-$DEPLOY_DIR/backups/pitr}"
keep="${PITR_KEEP:-7}"
container=""
recipient="${BACKUP_AGE_RECIPIENT:-}"
mode=base
moved_aside=false

die() { echo "basebackup: $*" >&2; exit 1; }
log() { echo "basebackup: $*" >&2; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }

while (($#)); do
  case "$1" in
    --wal-only) mode="wal-only"; shift ;;
    --new-cluster) mode="new-cluster"; shift ;;
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
sql() { docker exec "$container" psql -X -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres -c "$1"; }
in_archive() { docker exec "$container" sh -c 'cd "$0" && exec "$@"' "$ARCHIVE_IN_CONTAINER" "$@"; }
[[ "$(sql 'SHOW archive_mode')" == on ]] || die "archive_mode is not on in $container: WAL archiving is not configured (compose.yaml)"

mkdir -p "$dir"
# One run at a time per <dir>: a lock directory holding the owner's pid (mkdir is atomic; no flock on macOS).
lock="$dir/.lock"
locked=false
take_lock() {
  if mkdir "$lock" 2>/dev/null; then echo $$ >"$lock/pid"; locked=true; return 0; fi
  local owner
  owner="$(cat "$lock/pid" 2>/dev/null || true)"
  if [[ -n "$owner" ]] && ! kill -0 "$owner" 2>/dev/null; then
    log "removing the stale lock of pid $owner"
    rm -rf "$lock"
    take_lock
    return
  fi
  return 1
}
work=""
remote="" # base backup directory inside the container, while it exists
cleanup() {
  if [[ -n "$work" ]]; then rm -rf "$work"; fi
  if [[ -n "$remote" ]]; then docker exec "$container" rm -rf "$remote" >/dev/null 2>&1 || true; fi
  if $locked; then rm -rf "$lock"; fi
}
trap cleanup EXIT
if [[ "$mode" == wal-only ]]; then
  take_lock || { log "another run holds $lock (pid $(cat "$lock/pid" 2>/dev/null)); skipping, it syncs the WAL too"; exit 0; }
else
  waited=0
  until take_lock; do
    ((waited < LOCK_WAIT)) || die "another run still holds $lock after ${LOCK_WAIT}s (pid $(cat "$lock/pid" 2>/dev/null))"
    sleep 5
    waited=$((waited + 5))
  done
fi

# The cluster the archive and <dir> must belong to.
sysid="$(sql 'SELECT system_identifier FROM pg_control_system()')"
[[ "$sysid" =~ ^[0-9]+$ ]] || die "cannot read the system identifier of $container"
archive_owner="$(in_archive cat "$MARKER" 2>/dev/null || true)"
dir_owner="$(cat "$dir/$MARKER" 2>/dev/null || true)"

if [[ "$mode" == new-cluster ]]; then
  [[ "$archive_owner" != "$sysid" || "$dir_owner" != "$sysid" ]] \
    || die "the archive and $dir already belong to the running cluster $sysid: nothing to move aside"
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  if [[ -n "$archive_owner" && "$archive_owner" != "$sysid" ]]; then
    aside="old-$archive_owner-$stamp"
    # shellcheck disable=SC2016 # expanded by the container shell
    in_archive sh -c 'mkdir "$0" && for f in *; do [ -f "$f" ] && mv -- "$f" "$0/"; done; true' "$aside"
    log "moved the WAL of cluster $archive_owner aside to $ARCHIVE_IN_CONTAINER/$aside (delete it once you no longer need it)"
  fi
  if [[ -n "$dir_owner" && "$dir_owner" != "$sysid" ]]; then
    aside="$dir/old-$dir_owner-$stamp"
    mkdir "$aside"
    for f in base wal "$MARKER"; do if [[ -e "$dir/$f" ]]; then mv "$dir/$f" "$aside/"; fi; done
    log "moved the backups of cluster $dir_owner aside to $aside (they still restore to times before the change)"
  fi
  archive_owner=""
  dir_owner=""
  moved_aside=true
  mode=base
fi
other_cluster() { # $1 owner, $2 what
  die "$2 belongs to database cluster $1, but $container runs cluster $sysid (was the database volume recreated?).
  Nothing was changed. If this is the new cluster to keep, run: ./basebackup.sh --new-cluster
  (deploy/README.md, \"New database cluster\")."
}
[[ -z "$archive_owner" || "$archive_owner" == "$sysid" ]] || other_cluster "$archive_owner" "the pg-wal-archive volume"
[[ -z "$dir_owner" || "$dir_owner" == "$sysid" ]] || other_cluster "$dir_owner" "$dir"
if [[ -z "$archive_owner" ]]; then
  # shellcheck disable=SC2016 # expanded by the container shell
  in_archive sh -c 'echo "$0" >SYSTEM_IDENTIFIER.tmp && sync SYSTEM_IDENTIFIER.tmp && mv SYSTEM_IDENTIFIER.tmp SYSTEM_IDENTIFIER && sync .' "$sysid"
  log "the pg-wal-archive volume now belongs to cluster $sysid"
fi
if [[ -z "$dir_owner" ]]; then
  echo "$sysid" >"$dir/$MARKER"
  log "$dir now belongs to cluster $sysid"
fi

mkdir -p "$dir/base" "$dir/wal"
# Leftovers of killed runs: partial copies in <dir> (encrypted, or as plain as the backups themselves).
find "$dir/base" "$dir/wal" -maxdepth 1 -type f -name '*.tmp.*' -mmin +60 -exec rm -f {} +
[[ -n "$recipient" ]] || log "WARNING: backups are NOT encrypted (spec §10 asks for encrypted backups); set BACKUP_AGE_RECIPIENT"
# Plaintext staging stays outside <dir>, which is copied off the box.
work="$(mktemp -d "${TMPDIR:-/tmp}/rp-basebackup.XXXXXX")"

# Writes $1 to $2 (age-encrypted when a recipient is set), flushed to disk, appearing complete or not at all.
seal() {
  local tmp
  tmp="$(mktemp "$2.tmp.XXXXXX")"
  if [[ -n "$recipient" ]]; then age -r "$recipient" -o "$tmp" "$1"; else cp "$1" "$tmp"; fi
  sync "$tmp" # coreutils/busybox: fsync of the file; macOS: a global sync
  mv "$tmp" "$2"
  sync "$(dirname "$2")"
}

base_archive=""
if [[ "$mode" == base ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  remote="/tmp/rp-basebackup-$stamp"
  log "base backup of container $container (cluster $sysid)"
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
  name="restaurantpro-base-$stamp-$sysid-$start_wal.tar"
  [[ -n "$recipient" ]] && name="$name.age"
  base_archive="$dir/base/$name"
  [[ ! -e "$base_archive" ]] || die "$base_archive already exists"
  seal "$work/payload.tar" "$base_archive"
  rm -rf "$work/base" "$work/payload.tar"
  log "wrote $base_archive ($(wc -c <"$base_archive" | tr -d ' ') bytes, starts at WAL $start_wal)"
fi

# WAL sync: segments, timeline history and backup history files; never the .tmp of a copy in progress.
new=()
while IFS= read -r f; do
  [[ "$f" =~ $WAL_NAME ]] || continue
  [[ -e "$dir/wal/$f" || -e "$dir/wal/$f.age" ]] || new+=("$f")
done < <(in_archive ls -1)
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

if [[ "$mode" == base ]]; then
  bases=()
  while IFS= read -r f; do bases+=("$f"); done < <(cd "$dir/base" && find . -maxdepth 1 -type f \
    \( -name "restaurantpro-base-*Z-$sysid-*.tar" -o -name "restaurantpro-base-*Z-$sysid-*.tar.age" \) | sed 's|^\./||' | sort)
  for ((i = 0; i < ${#bases[@]} - keep; i++)); do
    rm -f -- "${dir:?}/base/${bases[i]}"
    log "retention: deleted base ${bases[i]}"
  done
  oldest="${bases[$((${#bases[@]} > keep ? ${#bases[@]} - keep : 0))]}"
  oldest_wal="$(sed -E 's/^restaurantpro-base-[0-9]{8}T[0-9]{6}Z-[0-9]+-([0-9A-F]{24})\.tar(\.age)?$/\1/' <<<"$oldest")"
  [[ "$oldest_wal" =~ ^[0-9A-F]{24}$ ]] || die "cannot read the start WAL segment of $oldest"
  # Names sort in WAL order within a timeline; history files are tiny and always kept.
  older() { local seg="${1:0:24}"; [[ "$seg" =~ ^[0-9A-F]{24}$ && "$seg" < "$oldest_wal" ]]; }
  pruned=0
  while IFS= read -r f; do
    if older "$f"; then rm -f -- "$dir/wal/$f"; pruned=$((pruned + 1)); fi
  done < <(cd "$dir/wal" && ls -1)
  # Every file in the volume was synced above, so this removes nothing that exists only there.
  stale=()
  while IFS= read -r f; do if older "$f"; then stale+=("$f"); fi; done < <(in_archive ls -1)
  if ((${#stale[@]})); then in_archive rm -f -- "${stale[@]}"; fi
  log "retention: WAL before $oldest_wal removed ($pruned file(s) in $dir/wal, ${#stale[@]} in the pg-wal-archive volume)"
fi

[[ -z "$base_archive" ]] || echo "$base_archive"

archive_failing() {
  sql "SELECT last_failed_wal FROM pg_stat_archiver WHERE last_failed_time > coalesce(last_archived_time, '-infinity'::timestamptz)"
}
failing="$(archive_failing)"
if [[ -n "$failing" ]] && $moved_aside; then
  # PostgreSQL retries a failed segment about once a minute; the move above removed the name clash.
  log "waiting for PostgreSQL to retry archiving $failing"
  for _ in $(seq 1 36); do
    sleep 5
    failing="$(archive_failing)"
    [[ -n "$failing" ]] || break
  done
fi
[[ -z "$failing" ]] || die "PostgreSQL cannot archive $failing (pg_stat_archiver): new WAL is not reaching the archive
  and pg_wal grows until the disk is full. See: docker compose logs postgres"
