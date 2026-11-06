#!/usr/bin/env bash
# Physical backup for point-in-time recovery (D90, spec §11: RPO 15 min). Pairs with pitr-restore.sh.
#
# PostgreSQL archives every finished WAL segment into the pg-wal-archive volume, age-encrypted to every
# recipient as <segment>.age plus its <segment>.sha256 sidecar (compose.yaml, postgres/archive-wal.sh, D98).
# This script copies that off the database volume:
#   default        a base backup (pg_basebackup -Ft -X stream, checked with pg_verifybackup), written to
#                    <dir>/base/restaurantpro-base-<UTC timestamp>-<system id>-<start WAL segment>.tar.age
#                  (and its .tar.sha256 sidecar), holding base.tar.gz, pg_wal.tar.gz, backup_manifest and
#                  SHA256SUMS; then the WAL sync below; then retention: keeps the newest --keep base backups
#                  and deletes the WAL older than the oldest kept one, in <dir>/wal and in the volume.
#   --wal-only     only the WAL sync: copies each archived <segment>.age and its sidecar not yet in <dir>/wal
#                  there, as they are (never re-encrypted). Run it every 5 minutes, then offbox-sync.sh.
#   --encrypt-legacy  upgrade from S27 (D98 rule 3e): encrypts the plaintext WAL in the volume, the plaintext
#                  base backups and WAL in <dir>, and the plaintext logical archives in $BACKUP_DIR (default
#                  deploy/backups), in place, removing each plaintext once its .age is on disk; then runs
#                  as default (a new base backup). In production a default run refuses plaintext and says so.
#   --new-cluster  start a new archive chain for the running cluster: the first run of a new installation,
#                  and after the database volume was recreated (a new cluster, whose WAL names start again).
#                  Moves aside whatever is not this cluster's healthy archive (another cluster's marker, files
#                  without a marker, or an archive PostgreSQL is failing to write into): in the volume to
#                  /wal-archive/old-<old system id|unmarked>-<timestamp>/, in <dir> to <dir>/old-<...>/; then
#                  runs as default. Refuses only when both markers name the running cluster and archiving works.
# The archive and <dir> each hold a SYSTEM_IDENTIFIER file naming the cluster they belong to (the
# system_identifier of pg_control_system()). A default run refuses, touching nothing, an archive or <dir>
# of another cluster, or one that holds files but no marker; it writes the marker only into an empty one.
# A run also fails, after doing its work, while PostgreSQL's archiving is failing.
# Lock: one run per <dir>. --wal-only skips (exit 0) while another run holds it, but fails once the lock
# is older than an hour; a base backup waits up to an hour.
# Production (RESTAURANTPRO_ENVIRONMENT, default production) refuses, writing nothing, without a recipients
# file of at least two keys, with an age identity on the server, or with loose modes or owners (D98). Test
# mode accepts a single BACKUP_AGE_RECIPIENT, and without any recipient writes plaintext with a WARNING.
#
# Usage: deploy/basebackup.sh [--wal-only | --new-cluster | --encrypt-legacy] [--dir DIR] [--keep N]
#                             [--container NAME] [--recipients-file FILE]
#   --dir              where backups go (default: $PITR_DIR or deploy/backups/pitr)
#   --keep             base backups to keep, newest first (default: $PITR_KEEP or 7)
#   --container        postgres container (default: the compose service "postgres" of deploy/compose.yaml)
#   --recipients-file  age recipients, as backup.sh (default: $BACKUP_AGE_RECIPIENTS_FILE or
#                      deploy/backup-recipients.txt)
# Prints the base backup path on stdout (nothing with --wal-only). Exits non-zero on any failure.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
ARCHIVE_IN_CONTAINER=/wal-archive
MARKER=SYSTEM_IDENTIFIER
LOCK_WAIT=3600 # seconds a base backup waits for a running --wal-only (or other) run
WAL_PLAIN='([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)'
WAL_NAME="^$WAL_PLAIN\$"                       # a plaintext WAL file (S27, or test mode)
WAL_ANY="^$WAL_PLAIN(\.age|\.sha256)?\$"         # any file of the archive
dir="${PITR_DIR:-$DEPLOY_DIR/backups/pitr}"
keep="${PITR_KEEP:-7}"
container=""
recipients=""
mode=base
moved_aside=false
encrypt_legacy=false

die() { echo "basebackup: $*" >&2; exit 1; }
log() { echo "basebackup: $*" >&2; }
sha256() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"

while (($#)); do
  case "$1" in
    --wal-only) mode="wal-only"; shift ;;
    --new-cluster) mode="new-cluster"; shift ;;
    --encrypt-legacy) encrypt_legacy=true; shift ;;
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --keep) keep="${2:?--keep needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --recipients-file) recipients="${2:?--recipients-file needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$keep" =~ ^[1-9][0-9]*$ ]] || die "--keep must be a positive integer"
[[ "$mode" == base ]] || ! $encrypt_legacy || die "--encrypt-legacy is a run of its own: not with --$mode"
env_mode
recipients="${recipients:-$(recipients_file)}"
legacy_dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}" # backup.sh's archives, for --encrypt-legacy
# Plaintext staging stays outside <dir>, which is copied off the box.
work="$(mktemp -d "${TMPDIR:-/tmp}/rp-basebackup.XXXXXX")"
remote="" # base backup directory inside the container, while it exists
locked=false
cleanup() {
  rm -rf "$work"
  if [[ -n "$RP_PARTIAL" ]]; then rm -f "$RP_PARTIAL"; fi
  if [[ -n "$remote" ]]; then docker exec "$container" rm -rf "$remote" >/dev/null 2>&1 || true; fi
  if $locked; then rm -rf "$lock"; fi
}
trap cleanup EXIT
# D98: every check before anything is written.
if production; then
  [[ -z "${BACKUP_AGE_RECIPIENT:-}" ]] || die "BACKUP_AGE_RECIPIENT (a single key) is for test mode only; production encrypts to the --recipients-file (operational + recovery key)"
  require_safe_recipients "$recipients" 2
  refuse_identity_on_server "$dir"
  require_safe_scripts
  if [[ -e "$dir" ]]; then require_private_dir "$dir"; fi
  if $encrypt_legacy && [[ -e "$legacy_dir" ]]; then require_private_dir "$legacy_dir"; fi
elif [[ -n "$(recipient_keys "$recipients" 2>/dev/null)" ]]; then
  require_safe_recipients "$recipients" 1
elif [[ -n "${BACKUP_AGE_RECIPIENT:-}" ]]; then
  echo "$BACKUP_AGE_RECIPIENT" >"$work/recipient.txt"
  recipients="$work/recipient.txt"
  require_safe_recipients "$recipients" 1
else
  recipients=""
  ! $encrypt_legacy || die "--encrypt-legacy needs recipients"
  log "WARNING: test mode without recipients: backups are NOT encrypted (developer stacks only)"
fi
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres)"
  [[ -n "$container" ]] || die "postgres service is not running (start the stack or pass --container)"
fi
sql() { docker exec "$container" psql -X -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres -c "$1"; }
in_archive() { docker exec "$container" sh -c 'cd "$0" && exec "$@"' "$ARCHIVE_IN_CONTAINER" "$@"; }
[[ "$(sql 'SHOW archive_mode')" == on ]] || die "archive_mode is not on in $container: WAL archiving is not configured (compose.yaml)"

mkdir -p "$dir"
# One run at a time per <dir>: a lock directory (mkdir is atomic; no flock on macOS) whose "owner" file
# holds the owner's pid and that process's start time, so a reused pid is not mistaken for the owner.
lock="$dir/.lock"
proc_start() { ps -o lstart= -p "$1" 2>/dev/null | sed 's/^ *//; s/ *$//'; } # empty when the pid is gone
lock_owner() { cat "$1/owner" 2>/dev/null | paste -sd '|' -; }             # "pid|start time"
lock_is_stale() { # $1 lock dir; a lock without an owner file is stale once it is a minute old
  local owner pid
  owner="$(lock_owner "$1")"
  if [[ -z "$owner" ]]; then [[ -n "$(find "$1" -maxdepth 0 -mmin +1 2>/dev/null)" ]]; return; fi
  pid="${owner%%|*}"
  [[ "$(proc_start "$pid")" != "${owner#*|}" ]]
}
take_lock() {
  if mkdir "$lock" 2>/dev/null; then
    printf '%s\n%s\n' $$ "$(proc_start $$)" >"$lock/owner"
    locked=true
    return 0
  fi
  lock_is_stale "$lock" || return 1
  # Move it aside first: of two runs clearing the same stale lock, only one mv succeeds.
  local aside="$lock.stale.$$"
  mv "$lock" "$aside" 2>/dev/null || return 1
  if ! lock_is_stale "$aside"; then # another run had already replaced it with a live lock: put it back
    [[ ! -e "$lock" ]] && mv "$aside" "$lock" && return 1
    die "moved a live lock aside to $aside and cannot put it back; check the running basebackup.sh"
  fi
  log "removed the stale lock of $(lock_owner "$aside" | tr '|' ' ')"
  rm -rf "$aside"
  take_lock
}
if [[ "$mode" == wal-only ]]; then
  if ! take_lock; then
    # Skipping is right while a base backup runs; a lock held longer than any run should take is not.
    [[ -z "$(find "$lock" -maxdepth 0 -mmin +$((LOCK_WAIT / 60)) 2>/dev/null)" ]] \
      || die "$lock is held for more than ${LOCK_WAIT}s by $(lock_owner "$lock" | tr '|' ' '): is that run stuck?"
    log "another run holds $lock ($(lock_owner "$lock" | tr '|' ' ')); skipping, it syncs the WAL too"
    exit 0
  fi
else
  waited=0
  until take_lock; do
    ((waited < LOCK_WAIT)) || die "another run still holds $lock after ${LOCK_WAIT}s ($(lock_owner "$lock" | tr '|' ' '))"
    sleep 5
    waited=$((waited + 5))
  done
fi

# The cluster the archive and <dir> must belong to.
sysid="$(sql 'SELECT system_identifier FROM pg_control_system()')"
[[ "$sysid" =~ ^[0-9]+$ ]] || die "cannot read the system identifier of $container"
archive_owner="$(in_archive cat "$MARKER" 2>/dev/null || true)"
dir_owner="$(cat "$dir/$MARKER" 2>/dev/null || true)"
archive_has_files() { [[ -n "$(in_archive ls -1 | grep -E "$WAL_ANY|\.tmp$" || true)" ]]; }
dir_has_files() { [[ -n "$(find "$dir/base" "$dir/wal" -type f 2>/dev/null | head -1)" ]]; }
archive_failing() {
  sql "SELECT last_failed_wal FROM pg_stat_archiver WHERE last_failed_time > coalesce(last_archived_time, '-infinity'::timestamptz)"
}

if [[ "$mode" == new-cluster ]]; then
  # Moves aside whatever is not provably this cluster's healthy archive; refuses only when it is.
  failing="$(archive_failing)"
  if [[ "$archive_owner" == "$sysid" && "$dir_owner" == "$sysid" && -z "$failing" ]]; then
    die "the archive and $dir already belong to the running cluster $sysid and archiving works: nothing to move aside"
  fi
  move_archive=false
  move_dir=false
  if [[ -n "$failing" ]]; then move_archive=true; move_dir=true; fi
  if [[ "$archive_owner" != "$sysid" ]] && { [[ -n "$archive_owner" ]] || archive_has_files; }; then move_archive=true; fi
  if [[ "$dir_owner" != "$sysid" ]] && { [[ -n "$dir_owner" ]] || dir_has_files; }; then move_dir=true; fi
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  if $move_archive; then
    aside="old-${archive_owner:-unmarked}-$stamp"
    # shellcheck disable=SC2016 # expanded by the container shell
    in_archive sh -ec 'mkdir "$0"; for f in *; do if [ -f "$f" ]; then mv -- "$f" "$0/"; fi; done' "$aside" \
      || die "could not move the old WAL aside to $ARCHIVE_IN_CONTAINER/$aside; nothing else was changed"
    log "moved the old WAL (cluster ${archive_owner:-unmarked}) aside to $ARCHIVE_IN_CONTAINER/$aside (delete it once you no longer need it)"
  fi
  if $move_dir; then
    aside="$dir/old-${dir_owner:-unmarked}-$stamp"
    mkdir "$aside"
    for f in base wal "$MARKER"; do if [[ -e "$dir/$f" ]]; then mv "$dir/$f" "$aside/"; fi; done
    log "moved the old backups (cluster ${dir_owner:-unmarked}) aside to $aside (they still restore to times before the change)"
  fi
  # Whatever is left now belongs to this cluster: only its own WAL can have arrived since the move.
  adopt=true
  moved_aside=true
  mode=base
else
  adopt=false
fi
other_cluster() { # $1 owner, $2 what
  die "$2 belongs to database cluster $1, but $container runs cluster $sysid (was the database volume recreated?).
  Nothing was changed. If this is the new cluster to keep, run: ./basebackup.sh --new-cluster
  (deploy/README.md, \"New database cluster\")."
}
unmarked() { # $1 what
  die "unmarked archive: $1 holds files but no $MARKER, so it cannot be told apart from another cluster's.
  Nothing was changed. Run: ./basebackup.sh --new-cluster (deploy/README.md, \"New database cluster\")."
}
if ! $adopt; then
  [[ -z "$archive_owner" || "$archive_owner" == "$sysid" ]] || other_cluster "$archive_owner" "the pg-wal-archive volume"
  [[ -z "$dir_owner" || "$dir_owner" == "$sysid" ]] || other_cluster "$dir_owner" "$dir"
  if [[ -z "$archive_owner" ]] && archive_has_files; then unmarked "the pg-wal-archive volume"; fi
  if [[ -z "$dir_owner" ]] && dir_has_files; then unmarked "$dir"; fi
fi
if [[ "$(in_archive cat "$MARKER" 2>/dev/null || true)" != "$sysid" ]]; then
  # shellcheck disable=SC2016 # expanded by the container shell
  in_archive sh -ec 'echo "$0" >SYSTEM_IDENTIFIER.tmp; sync SYSTEM_IDENTIFIER.tmp; mv SYSTEM_IDENTIFIER.tmp SYSTEM_IDENTIFIER; sync .' "$sysid"
  log "the pg-wal-archive volume now belongs to cluster $sysid"
fi
if [[ "$(cat "$dir/$MARKER" 2>/dev/null || true)" != "$sysid" ]]; then
  echo "$sysid" >"$dir/$MARKER"
  log "$dir now belongs to cluster $sysid"
fi

mkdir -p "$dir/base" "$dir/wal"
# Leftovers of killed runs: partial copies in <dir> (ciphertext, or test mode plaintext).
find "$dir/base" "$dir/wal" -maxdepth 1 -type f -name '*.tmp.*' -mmin +60 -exec rm -f {} +

# S27 plaintext (D98 rule 3e): WAL in the volume, base backups and WAL in <dir> (also in old-*/ set aside),
# and backup.sh archives in $legacy_dir. Production refuses to work next to it; --encrypt-legacy encrypts it.
volume_plaintext() { in_archive find . -type f | sed 's|^\./||' | while IFS= read -r f; do
  if [[ "$(basename "$f")" =~ $WAL_NAME ]]; then echo "$f"; fi; done; }
dir_plaintext() {
  find "$dir" -path "$dir/.lock" -prune -o -type f \( -name 'restaurantpro-base-*.tar' -o -path '*/wal/*' \) -print \
    | while IFS= read -r f; do if [[ "$f" == *.tar || "$(basename "$f")" =~ $WAL_NAME ]]; then echo "$f"; fi; done
}
logical_plaintext() {
  if [[ -d "$legacy_dir" ]]; then find "$legacy_dir" -maxdepth 1 -type f -name '*.tar'; fi
}
encrypt_in_place() { # $1 plaintext file on this host: $1.age and its sidecar, then the plaintext goes
  local f=$1
  if [[ -e "$f.age" ]]; then
    [[ "$(cut -d' ' -f1 "${f}.sha256" 2>/dev/null)" == "$(sha256_hex "$f")" ]] \
      || die "$f and $f.age both exist but the sidecar does not match: check them by hand"
  else
    is_age_file "$f" && die "$f is already age ciphertext but lacks the .age suffix: rename it to $f.age"
    seal "$f" "$f" "$recipients"
  fi
  rm -f -- "$f"
  sync "$(dirname "$f")"
}
if $encrypt_legacy; then
  n=0
  while IFS= read -r f; do
    # shellcheck disable=SC2016 # expanded by the container shell
    in_archive sh -ec 'umask 077; sh /etc/restaurantpro/archive-wal.sh "/wal-archive/$0" "$0"; chown postgres:postgres "$0.age" "$0.sha256"; rm -f -- "$0"; sync "$(dirname "$0")"' "$f" \
      || die "could not encrypt $f in the pg-wal-archive volume (is the recipients file mounted? docker compose up -d)"
    n=$((n + 1))
  done < <(volume_plaintext)
  log "--encrypt-legacy: encrypted $n plaintext WAL file(s) in the pg-wal-archive volume"
  n=0
  while IFS= read -r f; do encrypt_in_place "$f"; n=$((n + 1)); done < <(dir_plaintext)
  log "--encrypt-legacy: encrypted $n plaintext file(s) in $dir"
  n=0
  while IFS= read -r f; do encrypt_in_place "$f"; n=$((n + 1)); done < <(logical_plaintext)
  log "--encrypt-legacy: encrypted $n plaintext logical archive(s) in $legacy_dir; now a new base backup"
elif production; then
  legacy="$( (volume_plaintext | sed 's|^|pg-wal-archive volume: |'; dir_plaintext) | head -3 || true)"
  [[ -z "$legacy" ]] || die "plaintext backups from before D98 found, e.g.:
$legacy
  Production never writes next to plaintext. Run: ./basebackup.sh --encrypt-legacy (deploy/README.md, \"Upgrade from S27\")"
fi

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
  base_archive="$dir/base/restaurantpro-base-$stamp-$sysid-$start_wal.tar"
  [[ ! -e "$base_archive" && ! -e "$base_archive.age" ]] || die "$base_archive already exists"
  seal "$work/payload.tar" "$base_archive" "$recipients"
  [[ -z "$recipients" ]] || base_archive="$base_archive.age"
  rm -rf "$work/base" "$work/payload.tar"
  log "wrote $base_archive ($(wc -c <"$base_archive" | tr -d ' ') bytes, starts at WAL $start_wal)"
fi

# WAL sync: segments, timeline history and backup history files; never the .tmp of a copy in progress.
# Encrypted files are copied as they are, sidecar first (a <f>.age in the volume always has its sidecar);
# plaintext ones (test mode only, by now) are sealed like a base backup.
new=()   # <f>.age files to copy with their sidecars
plain=() # plaintext <f> files
while IFS= read -r f; do
  if [[ "$f" == *.age && "${f%.age}" =~ $WAL_NAME ]]; then
    [[ -e "$dir/wal/$f" ]] || new+=("$f")
  elif [[ "$f" =~ $WAL_NAME ]]; then
    [[ -e "$dir/wal/$f" || -e "$dir/wal/$f.age" ]] || plain+=("$f")
  fi
done < <(in_archive ls -1)
if ((${#new[@]} + ${#plain[@]})); then
  mkdir "$work/wal"
  for f in ${new[@]+"${new[@]}"}; do echo "${f%.age}.sha256"; echo "$f"; done >"$work/list"
  for f in ${plain[@]+"${plain[@]}"}; do echo "$f"; done >>"$work/list"
  docker exec -i "$container" tar -C "$ARCHIVE_IN_CONTAINER" -cf - -T - <"$work/list" | tar -xf - -C "$work/wal"
  for f in ${new[@]+"${new[@]}"}; do
    put_file "$work/wal/${f%.age}.sha256" "$dir/wal/${f%.age}.sha256"
    put_file "$work/wal/$f" "$dir/wal/$f"
  done
  for f in ${plain[@]+"${plain[@]}"}; do seal "$work/wal/$f" "$dir/wal/$f" "$recipients"; done
  rm -rf "$work/wal" "$work/list"
fi
log "WAL sync: $((${#new[@]} + ${#plain[@]})) new file(s) in $dir/wal"

if [[ "$mode" == base ]]; then
  bases=()
  while IFS= read -r f; do bases+=("$f"); done < <(cd "$dir/base" && find . -maxdepth 1 -type f \
    \( -name "restaurantpro-base-*Z-$sysid-*.tar" -o -name "restaurantpro-base-*Z-$sysid-*.tar.age" \) | sed 's|^\./||' | sort)
  for ((i = 0; i < ${#bases[@]} - keep; i++)); do
    rm -f -- "${dir:?}/base/${bases[i]}" "${dir:?}/base/${bases[i]%.tar*}.tar.sha256"
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
