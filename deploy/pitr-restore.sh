#!/usr/bin/env bash
# Point-in-time restore (D90, spec §11) of a basebackup.sh base backup plus archived WAL, into a NEW,
# isolated PostgreSQL container (no network, its own volume). The live installation is never touched:
# inspect the result, then bring it into the live database with backup.sh + restore.sh --force
# (deploy/README.md, "Point-in-time recovery").
#
# Steps: decrypt (.age), check SHA256SUMS, unpack the base backup into a new volume <name>-data,
# copy the WAL files into it, start PostgreSQL in archive recovery up to --target-time, wait until
# it has promoted, then remove the recovery settings and the WAL copy. PostgreSQL stops before the
# first transaction that committed after the target time. If the WAL does not reach the target,
# PostgreSQL refuses to finish ("recovery ended before configured recovery target was reached") and
# this script fails and removes what it created.
#
# Usage: deploy/pitr-restore.sh --target-time TIME [--name NAME] [--wal-dir DIR | --wal-volume VOLUME]
#                               [--identity AGE_KEY_FILE] [--timeout SECONDS] [--force] BASE_ARCHIVE
#   --target-time  ISO 8601 with an explicit offset, e.g. 2026-11-05T14:30:00Z or 2026-11-05T15:30:00+01:00;
#                  or "latest" to replay every archived WAL file
#   --name         container to create (default: restaurantpro-pitr); its volume is <name>-data
#   --wal-dir      directory of WAL files as written by basebackup.sh (<dir>/wal, .age files decrypted)
#   --wal-volume   Docker volume holding the WAL archive (default: restaurantpro_pg-wal-archive, the live
#                  installation's pg-wal-archive volume; mounted read-only)
#   --identity     age identity file, for .age files (default: $BACKUP_AGE_IDENTITY)
#   --timeout      seconds to wait for recovery (default: 1800)
#   --force        replace an existing container <name> and volume <name>-data
# Prints the container name on stdout. Exits non-zero on any failure.
set -euo pipefail
umask 077

PG_IMAGE="postgres:18.6-alpine" # D4: same pin as compose.yaml
DB_USER=restaurantpro
PGDATA_IN_IMAGE=/var/lib/postgresql/18/docker
WAL_COPY=/var/lib/postgresql/pitr-wal # inside the new volume, outside PGDATA; removed after recovery
WAL_NAME='^([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$'
target=""
name=restaurantpro-pitr
wal_dir=""
wal_volume=""
identity="${BACKUP_AGE_IDENTITY:-}"
timeout=1800
force=false
archive=""

die() { echo "pitr-restore: $*" >&2; exit 1; }
log() { echo "pitr-restore: $*" >&2; }
sha256_check() { if command -v sha256sum >/dev/null; then sha256sum -c --quiet "$@"; else shasum -a 256 -c --quiet "$@"; fi; }

while (($#)); do
  case "$1" in
    --target-time) target="${2:?--target-time needs a value}"; shift 2 ;;
    --name) name="${2:?--name needs a value}"; shift 2 ;;
    --wal-dir) wal_dir="${2:?--wal-dir needs a value}"; shift 2 ;;
    --wal-volume) wal_volume="${2:?--wal-volume needs a value}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a value}"; shift 2 ;;
    --timeout) timeout="${2:?--timeout needs a value}"; shift 2 ;;
    --force) force=true; shift ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    -*) die "unknown option: $1" ;;
    *) [[ -z "$archive" ]] || die "only one base archive allowed"; archive="$1"; shift ;;
  esac
done
[[ -n "$archive" ]] || die "usage: pitr-restore.sh --target-time TIME [options] BASE_ARCHIVE (see --help)"
[[ -f "$archive" ]] || die "base archive not found: $archive"
[[ -n "$target" ]] || die "--target-time is required (an ISO 8601 time with offset, or latest)"
# An explicit offset, because the restored server's time zone is not the operator's.
if [[ "$target" != latest ]] && ! [[ "$target" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}[T\ ][0-9]{2}:[0-9]{2}(:[0-9]{2}(\.[0-9]{1,6})?)?(Z|[+-][0-9]{2}(:?[0-9]{2})?)$ ]]; then
  die "--target-time must be ISO 8601 with an offset (e.g. 2026-11-05T14:30:00Z), or latest; got: $target"
fi
[[ "$name" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || die "invalid --name: $name"
[[ "$timeout" =~ ^[1-9][0-9]*$ ]] || die "--timeout must be a positive integer"
[[ -z "$wal_dir" || -z "$wal_volume" ]] || die "pass --wal-dir or --wal-volume, not both"
[[ -n "$wal_dir" ]] || wal_volume="${wal_volume:-restaurantpro_pg-wal-archive}"
if [[ -n "$wal_dir" ]]; then
  [[ -d "$wal_dir" ]] || die "WAL directory not found: $wal_dir"
else
  docker volume inspect "$wal_volume" >/dev/null 2>&1 || die "WAL volume not found: $wal_volume"
fi
volume="$name-data"

exists=()
docker container inspect "$name" >/dev/null 2>&1 && exists+=("container $name")
docker volume inspect "$volume" >/dev/null 2>&1 && exists+=("volume $volume")
if ((${#exists[@]})); then
  $force || die "${exists[*]} already exists; use --force to replace it, or pick another --name"
  log "--force: removing ${exists[*]}"
  docker rm -f -v "$name" >/dev/null 2>&1 || true
  docker volume rm "$volume" >/dev/null 2>&1 || true
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/rp-pitr.XXXXXX")"
created=false # true while the container/volume exist but recovery has not succeeded
cleanup() {
  local status=$?
  rm -rf "$work"
  if $created && ((status != 0)); then
    docker rm -f -v "$name" >/dev/null 2>&1 || true
    docker volume rm "$volume" >/dev/null 2>&1 || true
    log "removed $name and $volume after the failure"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

decrypt() { # $1 encrypted file, $2 output
  command -v age >/dev/null || die "age is not installed"
  [[ -n "$identity" ]] || die "encrypted file: pass --identity or set BACKUP_AGE_IDENTITY"
  age -d -i "$identity" -o "$2" "$1" || die "cannot decrypt $1"
}

if [[ "$archive" == *.age ]]; then decrypt "$archive" "$work/payload.tar"; else cp "$archive" "$work/payload.tar"; fi
log "verifying $archive"
mkdir "$work/base"
tar -xf "$work/payload.tar" -C "$work/base" SHA256SUMS backup_manifest base.tar.gz pg_wal.tar.gz \
  || die "not a basebackup.sh archive"
rm "$work/payload.tar"
(cd "$work/base" && sha256_check SHA256SUMS >&2) || die "checksum mismatch: archive is corrupted"

wal_mount=()
if [[ -n "$wal_dir" ]]; then
  mkdir "$work/wal"
  copied=0
  for f in "$wal_dir"/*; do
    [[ -f "$f" ]] || continue
    base="$(basename "$f")"
    if [[ "$base" == *.age && "${base%.age}" =~ $WAL_NAME ]]; then
      decrypt "$f" "$work/wal/${base%.age}"
    elif [[ "$base" =~ $WAL_NAME ]]; then
      cp "$f" "$work/wal/$base"
    else
      continue
    fi
    copied=$((copied + 1))
  done
  log "using $copied WAL file(s) from $wal_dir"
  wal_mount=(-v "$work/wal:/wal-source:ro")
else
  log "using the WAL archive in volume $wal_volume"
  wal_mount=(-v "$wal_volume:/wal-source:ro")
fi

log "unpacking the base backup into new volume $volume"
docker volume create --label restaurantpro.pitr="$name" "$volume" >/dev/null
created=true
recovery_target=""
# PostgreSQL rejects time zone abbreviations here, and "Z" counts as one: write it as +00:00.
[[ "$target" == latest ]] || recovery_target="recovery_target_time = '${target%Z}$([[ "$target" == *Z ]] && echo +00:00)'"
# The values are validated above and passed through the environment, never spliced into the script.
docker run --rm --network none -v "$volume:/var/lib/postgresql" -v "$work/base:/restore:ro" "${wal_mount[@]}" \
  -e RECOVERY_TARGET="$recovery_target" -e WAL_COPY="$WAL_COPY" -e WAL_NAME="$WAL_NAME" \
  --entrypoint sh "$PG_IMAGE" -euc '
    d='"$PGDATA_IN_IMAGE"'
    mkdir -p "$d" "$WAL_COPY"
    tar -xzf /restore/base.tar.gz -C "$d"
    tar -xzf /restore/pg_wal.tar.gz -C "$d/pg_wal"
    ls -1 /wal-source | grep -E "$WAL_NAME" | while IFS= read -r f; do cp "/wal-source/$f" "$WAL_COPY/$f"; done
    touch "$d/recovery.signal"
    {
      echo "# pitr-restore.sh: removed once recovery has finished"
      echo "restore_command = '\''cp $WAL_COPY/%f %p'\''"
      [ -z "$RECOVERY_TARGET" ] || echo "$RECOVERY_TARGET"
      echo "recovery_target_action = '\''promote'\''"
      echo "archive_mode = off"
    } >>"$d/postgresql.auto.conf"
    chown -R postgres:postgres /var/lib/postgresql
    chmod 700 "$d"
  '

log "starting $name (no network) and replaying WAL up to ${target}"
started=$SECONDS
docker run -d --name "$name" --network none --label restaurantpro.pitr="$name" \
  -v "$volume:/var/lib/postgresql" "$PG_IMAGE" >/dev/null
state=""
while ((SECONDS - started < timeout)); do
  if [[ "$(docker container inspect -f '{{.State.Running}}' "$name")" != true ]]; then
    docker logs --tail 15 "$name" >&2 2>&1 || true
    die "PostgreSQL stopped during recovery (see the log above)"
  fi
  state="$(docker exec "$name" psql -X -At -U "$DB_USER" -d postgres -c 'SELECT pg_is_in_recovery()' 2>/dev/null || true)"
  [[ "$state" == f ]] && break
  sleep 1
done
[[ "$state" == f ]] || die "recovery did not finish within ${timeout}s"

docker exec "$name" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
  -c 'ALTER SYSTEM RESET restore_command' -c 'ALTER SYSTEM RESET recovery_target_time' \
  -c 'ALTER SYSTEM RESET recovery_target_action' -c 'SELECT pg_reload_conf()' >/dev/null
docker exec "$name" rm -rf "$WAL_COPY"
docker logs "$name" 2>&1 | grep -E 'recovery stopping|last completed transaction|archive recovery complete|selected new timeline' \
  | sed 's/^/pitr-restore:   /' >&2 || true
log "done in $((SECONDS - started))s: container $name holds the database as of $target"
created=false
echo "$name"
