#!/usr/bin/env bash
# Key-free backup check on the server (D98 rule 6a, spec §12 "backup em atraso"). Run it every 15 minutes
# from cron: it exits non-zero, listing every problem, so the cron mail or the timer status alerts. It checks
#   - the archiver: archiving is on with archive_timeout <= 300 s, is not failing now, pg_stat_archiver's
#     failed_count has not grown since the last run, and no WAL segment has waited more than 15 minutes
#   - the copy: every segment archived more than 15 minutes ago is in <pitr dir>/wal
#   - the newest base backup is at most 26 hours old, and the WAL chain is continuous from the oldest kept one
#   - every file in the pg-wal-archive volume and in <dir> is age ciphertext whose well-formed .sha256 sidecar
#     holds its SHA-256 (bit rot or a swapped file is caught without a key), apart from the markers and the
#     sidecars (test mode: plaintext is only a WARNING). This reads every backup file once per run.
#   - modes and owners, the recipients file, no age identity on the server, the volume is postgres 0700
#   - the recipients: their fingerprint is kept in RECIPIENTS_SHA256 of <dir> and of <pitr dir> (and the file
#     as RECIPIENTS-<UTC>-<fingerprint>, which the off-box copy carries for verify-restore.sh); a change, or a
#     directory with ciphertext but no record, fails every run (and backup.sh / basebackup.sh refuse) until
#     --accept-recipients-change, which is logged. Only an empty installation is recorded silently.
#   - <dir>/LAST_OFFBOX_SYNC (offbox-sync.sh) is at most 30 minutes old
#
# Every run that gets past its arguments also writes the backup status file (D151, schema 1) that the backend
# reads for its in-app alerts and metrics (BACKUP_MONITOR_STALE, BACKUP_LATE, BACKUP_PROBLEM, DISK_SPACE_LOW):
# atomically (temporary file + mv), mode 0644 in a 0755 directory, also when the run fails (then "ok": false
# with the problems). It holds instants and sizes only: problems are stripped of paths, fingerprints and keys.
#   { "schemaVersion": 1, "checkedAt", "ok", "lastLogicalBackupAt", "lastBaseBackupAt", "lastOffboxSyncAt",
#     "problems": [...], "filesystems": [{"name": "backups"|"docker", "totalBytes", "freeBytes"}] }
# A missing instant is null; the docker entry is left out when Docker's root directory is not on this host's
# filesystem (Docker Desktop, or Docker unreachable).
#
# Usage: deploy/verify-backups.sh [--accept-recipients-change] [--dir DIR] [--pitr-dir DIR]
#                                 [--container NAME] [--recipients-file FILE] [--status-file FILE]
#        deploy/verify-backups.sh --self-test   runs deploy/test-monitoring.sh --self-test (status file fixture)
#   --dir          backup.sh's directory, copied off the box (default: $BACKUP_DIR or deploy/backups)
#   --pitr-dir     basebackup.sh's directory (default: $PITR_DIR or <dir>/pitr)
#   --status-file  the status file (default: $BACKUP_STATUS_FILE or deploy/status/backup-status.json, which
#                  compose.yaml mounts read-only into the backend)
# Prints a one-line summary on stderr when everything is fine.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
MAX_WAIT_MIN=15
MAX_BASE_AGE_MIN=$((26 * 60))
MAX_OFFBOX_AGE_MIN=30
MAX_STATUS_PROBLEMS=20
STATUS_SCHEMA_VERSION=1
DIE_MSG=""
die() { echo "verify-backups: $*" >&2; DIE_MSG="$*"; exit 1; }
log() { echo "verify-backups: $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"

dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
pitr="${PITR_DIR:-}"
container=""
recipients=""
accept=false
status_file="${BACKUP_STATUS_FILE:-$DEPLOY_DIR/status/backup-status.json}"
if [[ "${1:-}" == --self-test ]]; then exec "$DEPLOY_DIR/test-monitoring.sh" --self-test; fi
while (($#)); do
  case "$1" in
    --accept-recipients-change) accept=true; shift ;;
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --pitr-dir) pitr="${2:?--pitr-dir needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --recipients-file) recipients="${2:?--recipients-file needs a value}"; shift 2 ;;
    --status-file) status_file="${2:?--status-file needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

# The status file (D151), written on every run from here on, whatever happens.
iso_of_stamp() { sed -nE 's/^([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})Z$/\1-\2-\3T\4:\5:\6Z/p'; }
json_str() { printf '"%s"' "$(printf '%s' "$1" | tr -d '\000-\037' | sed 's/\\/\\\\/g; s/"/\\"/g')"; }
json_instant() { if [[ -n "$1" ]]; then json_str "$1"; else printf null; fi; }
redact() { # $1 a problem: no path, host path, fingerprint or key in the status file; at most 300 characters
  local s
  s="$(printf '%s' "$1" | tr '\n' ' ' | sed -E 's#[^[:space:]]*/([^/[:space:]]*)#\1#g; s#/# #g; s/[0-9a-fA-F]{64}/<fingerprint>/g; s/age1[02-9ac-hj-np-z]{58}/<recipient>/g; s/AGE-SECRET-KEY-1[02-9AC-HJ-NP-Z]{58}/<identity>/g')"
  printf '%s' "${s:0:300}"
}
newest_logical() { # the newest backup.sh archive, from its name
  find "$dir" -maxdepth 1 -type f \( -name 'restaurantpro-[0-9]*T[0-9]*Z.tar' -o -name 'restaurantpro-[0-9]*T[0-9]*Z.tar.age' \) 2>/dev/null \
    | sed -nE 's#^.*/restaurantpro-([0-9]{8}T[0-9]{6}Z)\.tar(\.age)?$#\1#p' | sort | tail -1 | iso_of_stamp
}
newest_base() { # the newest base backup, from its name
  find "${pitr:-$dir/pitr}/base" -maxdepth 1 -type f -name 'restaurantpro-base-*.tar*' ! -name '*.sha256' 2>/dev/null \
    | sed -nE 's#^.*/restaurantpro-base-([0-9]{8}T[0-9]{6}Z)-.*\.tar(\.age)?$#\1#p' | sort | tail -1 | iso_of_stamp
}
last_offbox() { # the instant offbox-sync.sh recorded
  cut -d' ' -f2 "$dir/LAST_OFFBOX_SYNC" 2>/dev/null | head -1 | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' || true
}
filesystem_json() { # $1 name, $2 path: {"name","totalBytes","freeBytes"} of the filesystem holding $2, or nothing
  local sizes
  [[ -n "$2" && -e "$2" ]] || return 0
  sizes="$(df -Pk "$2" 2>/dev/null | awk 'NR == 2 && $2 ~ /^[0-9]+$/ && $4 ~ /^[0-9]+$/ { printf "%.0f %.0f", $2 * 1024, $4 * 1024 }')" || return 0
  [[ -n "$sizes" ]] || return 0
  printf '{ "name": "%s", "totalBytes": %s, "freeBytes": %s }' "$1" "${sizes% *}" "${sizes#* }"
}
docker_root() { # Docker's root directory (db-data and pg-wal-archive live there), when Docker answers
  docker info -f '{{.DockerRootDir}}' 2>/dev/null | head -1 || true
}
write_status() { # $1 the run's exit status; writes $status_file atomically; returns non-zero if it could not
  local code=$1 ok=false sdir tmp p n=0 sep="" fs fss=() root
  if ((code == 0 && ${#problems[@]} == 0)); then ok=true; fi
  sdir="$(dirname "$status_file")"
  if [[ ! -d "$sdir" ]]; then # a new directory is 0755; an existing one keeps the installer's mode and owner
    mkdir -p "$sdir" && chmod 0755 "$sdir" || return 1
  fi
  tmp="$(mktemp "$status_file.tmp.XXXXXX")" || return 1
  {
    printf '{ "schemaVersion": %s,\n' "$STATUS_SCHEMA_VERSION"
    printf '  "checkedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "ok": %s,\n' "$ok"
    printf '  "lastLogicalBackupAt": %s,\n' "$(json_instant "$(newest_logical)")"
    printf '  "lastBaseBackupAt": %s,\n' "$(json_instant "$(newest_base)")"
    printf '  "lastOffboxSyncAt": %s,\n' "$(json_instant "$(last_offbox)")"
    printf '  "problems": ['
    for p in ${problems[@]+"${problems[@]}"}; do
      n=$((n + 1))
      if ((n > MAX_STATUS_PROBLEMS)); then
        printf '%s%s' "$sep" "$(json_str "and $((${#problems[@]} - MAX_STATUS_PROBLEMS)) more problem(s): see the output of verify-backups.sh")"
        break
      fi
      printf '%s%s' "$sep" "$(json_str "$(redact "$p")")"
      sep=", "
    done
    printf '],\n'
    fs="$(filesystem_json backups "$dir")"
    if [[ -n "$fs" ]]; then fss+=("$fs"); fi
    root="$(docker_root)"
    fs="$(filesystem_json docker "$root")"
    if [[ -n "$fs" ]]; then
      fss+=("$fs")
    else
      log "the docker filesystem is left out of the status file: Docker's root directory (${root:-Docker unreachable}) is not on this host"
    fi
    sep=""
    printf '  "filesystems": ['
    for fs in ${fss[@]+"${fss[@]}"}; do printf '%s%s' "$sep" "$fs"; sep=", "; done
    printf '] }\n'
  } >"$tmp" && chmod 0644 "$tmp" && mv -f "$tmp" "$status_file" && return 0
  rm -f "$tmp"
  return 1
}
problems=()
on_exit() {
  local code=$?
  trap - EXIT
  set +e
  remove_partials
  if ((code != 0)); then
    if [[ -n "$DIE_MSG" ]]; then
      problems+=("$DIE_MSG")
    elif ((code == 129 || code == 130 || code == 143)); then
      problems+=("verify-backups.sh was stopped by a signal (exit $code) before it finished its checks")
    elif ((${#problems[@]} == 0)); then
      problems+=("verify-backups.sh stopped unexpectedly (exit $code)")
    fi
  fi
  if ! write_status "$code"; then
    echo "verify-backups: PROBLEM: could not write the status file $status_file: the backend will report BACKUP_MONITOR_STALE" >&2
    code=1
  fi
  exit "$code"
}
trap on_exit EXIT
# A signal ends the run with a non-zero status, so the status file says "ok": false (bash would otherwise run the
# EXIT trap with the last command's status, often 0).
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

env_mode
pitr="${pitr:-$dir/pitr}"
recipients="${recipients:-$(recipients_file)}"
[[ -d "$dir" ]] || die "backup directory not found: $dir"
if [[ -z "$container" ]]; then
  container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres 2>/dev/null || true)"
fi

problem() { problems+=("$*"); }
check() { # runs a die-style check in a subshell; its message becomes a problem
  local out
  if ! out="$("$@" 2>&1)"; then problem "${out#verify-backups: }"; fi
}
plaintext() { # $1 what: a problem in production, a WARNING in test mode
  if production; then problem "$1"; else log "WARNING (test mode): $1"; fi
}

# Modes, owners, identities, recipients.
if production; then
  check require_private_dir "$dir"
  if [[ -d "$pitr" && "$pitr" != "$dir/"* ]]; then check require_private_dir "$pitr"; fi
  check require_safe_recipients "$recipients" 2
  check refuse_identity_on_server "$dir" "$pitr"
  check require_safe_scripts
fi
# The recipients, per backup directory: <dir> (backup.sh) and <pitr dir> (basebackup.sh) each record theirs.
if [[ -f "$recipients" ]]; then
  fingerprint="$(recipients_fingerprint "$recipients")"
  for d in "$dir" "$pitr"; do
    [[ -d "$d" ]] || continue
    known="$(cat "$d/RECIPIENTS_SHA256" 2>/dev/null || true)"
    if [[ "$known" == "$fingerprint" ]]; then
      [[ -n "$(recipients_copy "$d" "$fingerprint")" ]] || record_recipients "$d" "$recipients"
    elif $accept; then
      record_recipients "$d" "$recipients"
      log "AUDIT: recipients for $d accepted by uid $(id -u) ($(id -un)) at $(date -u +%Y-%m-%dT%H:%M:%SZ): ${known:-none recorded} -> $fingerprint"
    elif [[ -n "$known" ]]; then
      problem "the recipients changed since they were recorded for $d ($known -> $fingerprint): if you rotated a key on purpose, run ./verify-backups.sh --accept-recipients-change; otherwise someone may have swapped in their own key"
    elif [[ -n "$(unrecorded_ciphertext "$d")" ]]; then
      problem "$d holds encrypted backups but no RECIPIENTS_SHA256: if $recipients holds the right keys, run ./verify-backups.sh --accept-recipients-change"
    else
      record_recipients "$d" "$recipients" # an empty installation
      log "recorded the recipients (fingerprint $fingerprint) for $d"
    fi
  done
fi

# Every file is a marker, a well-formed sidecar, or ciphertext matching the ciphertext SHA-256 in its sidecar.
scan() { # $1 where; stdin: backup_file_problem lines; counts them in $found
  local line
  found=0
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    found=$((found + 1))
    if [[ "$line" == "not age ciphertext: "* ]]; then plaintext "$1: $line"; else problem "$1: $line"; fi
  done
}
local_problems() {
  find "$dir" "$pitr" -path "$dir/.lock" -prune -o -path "$pitr/.lock" -prune -o -type f ! -name '*.tmp.*' ! -name '*.tmp' -print 2>/dev/null \
    | sort -u | while IFS= read -r f; do backup_file_problem "$f"; done
}
scan "$dir" < <(local_problems)
dir_count="$(find "$dir" "$pitr" -type f -name '*.age' 2>/dev/null | sort -u | wc -l | tr -d ' ')"

if [[ -z "$container" ]]; then
  problem "the postgres service is not running (or pass --container): the archiver and the WAL volume are unchecked"
else
  sql() { docker exec "$container" psql -X -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres -c "$1"; }
  # The same rules, run in the container with the same lib/common.sh (mounted there by compose.yaml).
  # shellcheck disable=SC2016 # expanded by the container shell
  scan "the pg-wal-archive volume" < <(docker exec "$container" bash -c 'die() { echo "$*"; exit 1; }; . /etc/restaurantpro/common.sh
    find /wal-archive -type f ! -name "*.tmp" | sort | while IFS= read -r f; do backup_file_problem "$f"; done' \
    || echo "cannot scan the volume (is lib/common.sh mounted at /etc/restaurantpro/common.sh? docker compose up -d)")
  volume_count="$(docker exec "$container" find /wal-archive -type f -name '*.age' | wc -l | tr -d ' ')"
  [[ "$(docker exec "$container" stat -c '%a %U' /wal-archive)" == "700 postgres" ]] \
    || problem "the pg-wal-archive volume is not postgres 0700: $(docker exec "$container" stat -c '%a %U' /wal-archive)"

  [[ "$(sql 'SHOW archive_mode')" == on ]] || problem "archive_mode is not on"
  [[ "$(sql "SELECT setting::int <= 300 AND setting::int > 0 FROM pg_settings WHERE name = 'archive_timeout'")" == t ]] \
    || problem "archive_timeout is not between 1 and 300 s: the 15 min RPO is not met"
  failing="$(sql "SELECT last_failed_wal FROM pg_stat_archiver WHERE last_failed_time > coalesce(last_archived_time, '-infinity'::timestamptz)")"
  [[ -z "$failing" ]] || problem "PostgreSQL cannot archive $failing now (pg_stat_archiver; docker compose logs postgres)"
  failed_count="$(sql 'SELECT failed_count FROM pg_stat_archiver')"
  last_count="$(cat "$pitr/ARCHIVER_FAILED_COUNT" 2>/dev/null || echo "$failed_count")"
  ((failed_count <= last_count)) || problem "the archiver failed $((failed_count - last_count)) time(s) since the last check (pg_stat_archiver.failed_count $last_count -> $failed_count)"
  if [[ -d "$pitr" ]]; then
    printf '%s\n' "$failed_count" | put_file /dev/stdin "$pitr/ARCHIVER_FAILED_COUNT"
  fi
  waiting="$(sql "SELECT count(*) FROM pg_ls_archive_statusdir() WHERE name LIKE '%.ready' AND modification < now() - interval '$MAX_WAIT_MIN minutes'")"
  ((waiting == 0)) || problem "$waiting WAL segment(s) have waited more than $MAX_WAIT_MIN min to be archived: the last archive is too old"
  # Copied from the volume within 15 minutes (basebackup.sh --wal-only every 5 minutes).
  # shellcheck disable=SC2016 # expanded by the container shell
  while IFS= read -r f; do
    [[ -n "$f" && ! -e "$pitr/wal/$f" ]] && problem "$f has been in the pg-wal-archive volume for more than $MAX_WAIT_MIN min but is not in $pitr/wal (is basebackup.sh --wal-only running?)"
  done < <(docker exec "$container" sh -c 'cd /wal-archive && find . -maxdepth 1 -type f -name "*.age" -mmin +"$0" | sed "s|^\./||"' "$MAX_WAIT_MIN")
fi

# The newest base backup, and a continuous WAL chain from the oldest kept one.
bases="$(find "$pitr/base" -maxdepth 1 -type f \( -name 'restaurantpro-base-*.tar.age' -o -name 'restaurantpro-base-*.tar' \) 2>/dev/null | sort || true)"
if [[ -z "$bases" ]]; then
  problem "no base backup in $pitr/base (basebackup.sh nightly)"
else
  [[ -n "$(find "$pitr/base" -maxdepth 1 -type f -name 'restaurantpro-base-*.tar*' ! -name '*.sha256' -mmin -$MAX_BASE_AGE_MIN)" ]] \
    || problem "the newest base backup is older than 26 h: $(basename "$(tail -1 <<<"$bases")")"
  oldest_wal="$(head -1 <<<"$bases" | sed -E 's/^.*-([0-9A-F]{24})\.tar(\.age)?$/\1/')"
  # A base backup younger than the archiving bound may not have its first segment archived yet.
  fresh=false
  [[ -z "$(find "$(head -1 <<<"$bases")" -mmin -$MAX_WAIT_MIN)" ]] || fresh=true
  gap=""
  prev_tl=""
  prev=0
  while IFS= read -r seg; do
    [[ "$seg" < "$oldest_wal" ]] && continue
    tl="${seg:0:8}"
    n=$((16#${seg:8:8} * 256 + 16#${seg:16:8})) # 16 MB segments: 256 per log id
    if [[ "$tl" == "$prev_tl" ]] && ((n != prev + 1)); then gap="after $prev_seg, before $seg"; break; fi
    if [[ -z "$prev_tl" && "$seg" != "$oldest_wal" ]] && ! $fresh; then gap="the oldest kept base starts at $oldest_wal, the WAL at $seg"; break; fi
    prev_tl="$tl"
    prev=$n
    prev_seg="$seg"
  done < <(find "$pitr/wal" -maxdepth 1 -type f \( -name '*.age' -o -name '[0-9A-F]*' \) 2>/dev/null | sed 's|.*/||' \
    | grep -E '^[0-9A-F]{24}(\.age)?$' | cut -c1-24 | sort -u)
  if [[ -z "$prev_tl" && -z "$gap" ]] && ! $fresh; then gap="no WAL at or after $oldest_wal"; fi
  [[ -z "$gap" ]] || problem "the WAL chain in $pitr/wal is broken: $gap"
fi

# The off-box copy.
if [[ ! -f "$dir/LAST_OFFBOX_SYNC" ]]; then
  problem "no off-box copy recorded: $dir/LAST_OFFBOX_SYNC is missing (offbox-sync.sh)"
elif [[ -z "$(find "$dir/LAST_OFFBOX_SYNC" -mmin -$MAX_OFFBOX_AGE_MIN)" ]]; then
  problem "the last off-box copy is older than $MAX_OFFBOX_AGE_MIN min: $(cut -d' ' -f2 "$dir/LAST_OFFBOX_SYNC")"
fi

if ((${#problems[@]})); then
  for p in "${problems[@]}"; do echo "verify-backups: PROBLEM: $p" >&2; done
  exit 1
fi
log "OK ($RP_ENV): ${dir_count} encrypted file(s) in $dir, ${volume_count:-0} in the WAL volume, newest base $(basename "$(tail -1 <<<"$bases")"), off-box copy $(cut -d' ' -f2 "$dir/LAST_OFFBOX_SYNC")"
