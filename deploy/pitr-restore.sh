#!/usr/bin/env bash
# Point-in-time restore (D90, spec §11) of a basebackup.sh base backup plus archived WAL, into a NEW,
# isolated PostgreSQL container (no network, its own volume). The live installation is never touched:
# inspect the result, then bring it into the live database with backup.sh + restore.sh --force
# (deploy/README.md, "Point-in-time recovery").
#
# Steps, in a preparing container of the pinned image (no network) that alone sees the identity, read-only
# (D98): decrypt the base archive (.age) into the new volume <name>-data, check it against its sidecar and
# SHA256SUMS, run pg_verifybackup, unpack it, then decrypt each <segment>.age WAL file (checked against its
# <segment>.sha256 sidecar) into the same volume. Plaintext exists only inside that volume, as in any
# database volume. Then start PostgreSQL in archive recovery up to --target-time, wait until it has
# promoted, and remove the recovery settings and the WAL copy. PostgreSQL stops before the
# first transaction that committed after the target time. If the WAL does not reach the target,
# PostgreSQL refuses to finish ("recovery ended before configured recovery target was reached") and
# this script fails and removes what it created.
#
# Usage: deploy/pitr-restore.sh --target-time TIME [--name NAME] [--wal-dir DIR | --wal-volume VOLUME]
#                               [--identity AGE_KEY_FILE] [--timeout SECONDS] [--force] BASE_ARCHIVE
#   --target-time  ISO 8601 with an explicit offset, e.g. 2026-11-05T14:30:00Z or 2026-11-05T15:30:00+01:00;
#                  or "latest" to replay every archived WAL file
#   --name         container to create (default: restaurantpro-pitr); its volume is <name>-data
#   --wal-dir      directory of WAL files as written by basebackup.sh (<dir>/wal; mounted read-only)
#   --wal-volume   Docker volume holding the WAL archive (default: restaurantpro_pg-wal-archive, the live
#                  installation's pg-wal-archive volume; mounted read-only)
#   --identity     age identity file, for .age files (default: $BACKUP_AGE_IDENTITY). It must be yours, mode
#                  0600 or 0400, outside deploy/ and the backup directories (D98 rule 2), e.g. on a tmpfs
#   --timeout      seconds to wait for recovery (default: 1800)
#   --force        replace the container <name> and volume <name>-data of an earlier pitr-restore.sh run
#                  (label restaurantpro.pitr=<name>); anything else, Compose-managed objects above all
#                  (Compose's config-hash label on containers, its volume label on volumes), is refused even
#                  with --force
# Prints the container name on stdout. Exits non-zero on any failure.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB_USER=restaurantpro
PGDATA_IN_IMAGE=/var/lib/postgresql/18/docker
WAL_COPY=/var/lib/postgresql/pitr-wal # inside the new volume, outside PGDATA; removed after recovery
WAL_NAME='^([0-9A-F]{24}(\.partial|\.[0-9A-F]{8}\.backup)?|[0-9A-F]{8}\.history)$'
STAGING=/var/lib/postgresql/pitr-staging # the decrypted base archive, inside the new volume; removed at once
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
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
PG_IMAGE="$RP_AGE_IMAGE" # D4 PostgreSQL plus the pinned age (deploy/postgres/Dockerfile), as compose.yaml

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
env_mode
sidecar="${archive%.age}"
sidecar="${sidecar%.tar}.tar.sha256"
allow_plain_wal=1 # plaintext WAL only next to a plaintext base, or in test mode
if [[ "$archive" == *.age ]]; then
  [[ -n "$identity" ]] || die "encrypted base archive: pass --identity or set BACKUP_AGE_IDENTITY"
  ! production || allow_plain_wal=""
  if [[ -e "$sidecar" ]]; then
    is_sidecar "$sidecar" || die "$sidecar is not a well-formed sidecar"
    # Key-free, before anything is created: damaged or swapped ciphertext.
    [[ "$(sha256_hex "$archive")" == "$(sidecar_cipher "$sidecar")" ]] || die "checksum mismatch: $archive does not match the ciphertext SHA-256 in $sidecar"
  elif production; then
    die "no sidecar $sidecar next to $archive: copy it too (production restores check it)"
  fi
fi
if [[ -n "$identity" ]]; then
  backup_dir="$(dirname "$archive")"
  [[ "$(basename "$backup_dir")" != base ]] || backup_dir="$(dirname "$backup_dir")" # <dir>/base/<archive>
  require_identity "$identity" "$backup_dir" ${wal_dir:+"$wal_dir"}
fi
age_image_ready
volume="$name-data"

# --force replaces only what an earlier pitr-restore.sh --name "$name" created (label restaurantpro.pitr),
# never a Compose-managed object such as the live database container or volume. Compose-managed means a
# label Compose puts on what it creates and nothing inherits: com.docker.compose.config-hash on containers
# (a container started from a Compose-built image, as a restored one is, inherits the image's project and
# service labels), com.docker.compose.volume on volumes (images never reach volumes; every Compose v2 version
# stamps it, config-hash on volumes only recent ones). Same rule as lib/common.sh require_own_installation.
replaceable() { # $1 container|volume, $2 object name; "absent", "ours" or a reason to refuse
  local labels managed='"com.docker.compose.config-hash"'
  [[ $1 == container ]] || managed='"com.docker.compose.volume"'
  if ! labels="$(docker "$1" inspect -f '{{json .'"$([[ $1 == container ]] && echo Config.)"'Labels}}' "$2" 2>/dev/null)"; then
    echo absent; return
  fi
  if [[ "$labels" == *"$managed"* ]]; then
    echo "$1 $2 belongs to a Docker Compose project (the live installation?)"
  elif [[ "$labels" == *"\"restaurantpro.pitr\":\"$name\""* ]]; then
    echo ours
  else
    echo "$1 $2 was not created by pitr-restore.sh --name $name"
  fi
}
for kind in container volume; do
  object="$name"; [[ "$kind" == volume ]] && object="$volume"
  verdict="$(replaceable "$kind" "$object")"
  [[ "$verdict" == absent ]] && continue
  [[ "$verdict" == ours ]] || die "$verdict; refusing to touch it: pick another --name"
  $force || die "$kind $object already exists; use --force to replace it, or pick another --name"
  log "--force: removing $kind $object"
  if [[ "$kind" == container ]]; then docker rm -f -v "$object" >/dev/null; else docker volume rm "$object" >/dev/null; fi
  [[ "$(replaceable "$kind" "$object")" == absent ]] || die "could not remove $kind $object"
done

created=false # true once this run created the volume; on failure it removes only what it created
cid=""
cleanup() {
  local status=$?
  if $created && ((status != 0)); then
    if [[ -n "$cid" ]]; then docker rm -f -v "$cid" >/dev/null 2>&1 || log "could not remove container $name"; fi
    docker volume rm "$volume" >/dev/null 2>&1 || log "could not remove volume $volume"
    log "removed $name and $volume after the failure"
  fi
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# Read-only inputs of the preparing container; the identity is mounted into it alone.
mounts=(-v "$(abs_path "$archive"):/backup/payload:ro")
if [[ -f "$sidecar" ]]; then mounts+=(-v "$(abs_path "$sidecar"):/backup/payload.sha256:ro"); fi
if [[ -n "$identity" ]]; then mounts+=(-v "$(abs_path "$identity"):/run/rp/identity:ro"); fi
if [[ -n "$wal_dir" ]]; then
  log "using the WAL files in $wal_dir"
  mounts+=(-v "$(abs_dir "$wal_dir"):/wal-source:ro")
else
  log "using the WAL archive in volume $wal_volume"
  mounts+=(-v "$wal_volume:/wal-source:ro")
fi

log "verifying $archive and unpacking it into new volume $volume"
# "docker volume create" silently reuses an existing volume: make sure this run creates it.
[[ "$(replaceable volume "$volume")" == absent ]] || die "volume $volume appeared meanwhile; refusing to reuse it"
docker volume create --label restaurantpro.pitr="$name" "$volume" >/dev/null
created=true
recovery_target=""
# PostgreSQL rejects time zone abbreviations here, and "Z" counts as one: write it as +00:00.
[[ "$target" == latest ]] || recovery_target="recovery_target_time = '${target%Z}$([[ "$target" == *Z ]] && echo +00:00)'"
# The values are validated above and passed through the environment, never spliced into the script.
docker run --rm --network none -v "$volume:/var/lib/postgresql" "${mounts[@]}" \
  -e RECOVERY_TARGET="$recovery_target" -e WAL_COPY="$WAL_COPY" -e WAL_NAME="$WAL_NAME" -e STAGING="$STAGING" \
  -e ENCRYPTED="$([[ "$archive" == *.age ]] && echo 1 || true)" -e ALLOW_PLAIN_WAL="$allow_plain_wal" \
  --entrypoint sh "$PG_IMAGE" -euc '
    say() { echo "pitr-restore: $*" >&2; }
    decrypt() { # $1 ciphertext, $2 plaintext output
      [ -f /run/rp/identity ] || { say "$1 is encrypted: pass --identity"; exit 1; }
      age -d -i /run/rp/identity -o "$2" "$1" 2>/dev/null || { say "cannot decrypt $1 with this identity (wrong key?)"; exit 1; }
    }
    matches() { # $1 file, $2 sidecar, $3 line: 1 = plaintext, 2 = ciphertext SHA-256
      [ "$(sha256sum "$1" | cut -d" " -f1)" = "$(sed -n "$3p" "$2" | cut -d" " -f1)" ] || { say "checksum mismatch against ${2##*/}: corrupted"; exit 1; }
    }
    d='"$PGDATA_IN_IMAGE"'
    mkdir -p "$d" "$WAL_COPY" "$STAGING"
    if [ -n "$ENCRYPTED" ]; then decrypt /backup/payload "$STAGING/payload.tar"; else cp /backup/payload "$STAGING/payload.tar"; fi
    if [ -f /backup/payload.sha256 ]; then matches "$STAGING/payload.tar" /backup/payload.sha256 1; fi
    tar -xf "$STAGING/payload.tar" -C "$STAGING" SHA256SUMS backup_manifest base.tar.gz pg_wal.tar.gz       || { say "not a basebackup.sh archive"; exit 1; }
    rm "$STAGING/payload.tar"
    (cd "$STAGING" && sha256sum -c SHA256SUMS >/dev/null) || { say "checksum mismatch: archive is corrupted"; exit 1; }
    rm "$STAGING/SHA256SUMS" # pg_verifybackup accepts only the backup files themselves
    pg_verifybackup -n "$STAGING" >&2 || { say "pg_verifybackup rejected the base backup"; exit 1; }
    tar -xzf "$STAGING/base.tar.gz" -C "$d"
    tar -xzf "$STAGING/pg_wal.tar.gz" -C "$d/pg_wal"
    rm -rf "$STAGING"
    n=0
    for p in /wal-source/*; do
      [ -f "$p" ] || continue
      f="${p##*/}"
      if [ "${f%.age}" != "$f" ] && echo "${f%.age}" | grep -qE "$WAL_NAME"; then
        [ -f "/wal-source/${f%.age}.sha256" ] || { say "$f has no ${f%.age}.sha256 sidecar"; exit 1; }
        matches "$p" "/wal-source/${f%.age}.sha256" 2
        decrypt "$p" "$WAL_COPY/${f%.age}"
        matches "$WAL_COPY/${f%.age}" "/wal-source/${f%.age}.sha256" 1
      elif echo "$f" | grep -qE "$WAL_NAME" && [ ! -e "$p.age" ]; then
        [ -n "$ALLOW_PLAIN_WAL" ] || { say "plaintext WAL $f next to an encrypted base in production: refusing it (basebackup.sh --encrypt-legacy)"; exit 1; }
        cp "$p" "$WAL_COPY/$f"
      else
        continue
      fi
      n=$((n + 1))
    done
    say "base backup verified (SHA256SUMS, pg_verifybackup); $n WAL file(s) ready in the volume"
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
cid="$(docker run -d --name "$name" --network none --label restaurantpro.pitr="$name" \
  -v "$volume:/var/lib/postgresql" "$PG_IMAGE")"
state=""
while ((SECONDS - started < timeout)); do
  if [[ "$(docker container inspect -f '{{.State.Running}}' "$cid")" != true ]]; then
    docker logs --tail 15 "$cid" >&2 2>&1 || true
    die "PostgreSQL stopped during recovery (see the log above)"
  fi
  state="$(docker exec "$cid" psql -X -At -U "$DB_USER" -d postgres -c 'SELECT pg_is_in_recovery()' 2>/dev/null || true)"
  [[ "$state" == f ]] && break
  sleep 1
done
[[ "$state" == f ]] || die "recovery did not finish within ${timeout}s"

docker exec "$cid" psql -X -q -v ON_ERROR_STOP=1 -U "$DB_USER" -d postgres \
  -c 'ALTER SYSTEM RESET restore_command' -c 'ALTER SYSTEM RESET recovery_target_time' \
  -c 'ALTER SYSTEM RESET recovery_target_action' -c 'SELECT pg_reload_conf()' >/dev/null
docker exec "$cid" rm -rf "$WAL_COPY"
docker logs "$cid" 2>&1 | grep -E 'recovery stopping|last completed transaction|archive recovery complete|selected new timeline' \
  | sed 's/^/pitr-restore:   /' >&2 || true
log "done in $((SECONDS - started))s: container $name holds the database as of $target"
created=false
echo "$name"
