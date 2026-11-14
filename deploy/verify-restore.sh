#!/usr/bin/env bash
# Restore check on a RESTORE HOST, never on the server (D98 rules 6b, 6c). Needs only Docker, the deploy/
# scripts and a local copy of the off-box destination (rsync it down from the off-box host first).
#   weekly          with the operational identity
#   --key-recovery  quarterly, on a clean machine, with the RECOVERY identity alone, retrieved from escrow by
#                   the second custodian (deploy/README.md, "Key-recovery drill"); the identity file must hold
#                   exactly one key, and --retrieval-started times the retrieval against the 2 h RTO (§11)
# Steps: the identity's public key must be one of the copied recipients (the newest RECIPIENTS-<UTC>-<sha256>,
# whose keys must match its name); pitr-restore.sh --target-time latest of the newest base backup with all copied WAL
# into an isolated container (it runs pg_verifybackup before the start); recovery must reach the newest copied
# WAL segment; pg_amcheck --all must be clean; the Flyway history must have no failed row; the database roles must be
# as ADR 0039 (D163) leaves them (restaurantpro_owner owns every object, restaurantpro_app owns nothing, has data
# privileges only, no write on the Flyway history, no console function; row-level security forced with its policies).
# Then restore.sh of the newest backup.sh archive into a scratch container, with the same checks (restore.sh
# provisions the scratch roles with throwaway passwords: the installation's secrets never come to the restore host). Writes
# <report dir>/drill-<UTC>.json (base, WAL reached, RTO, key) and removes every container and volume it made.
#
# Usage: deploy/verify-restore.sh --from DIR --identity FILE [--key-recovery] [--retrieval-started TIME]
#                                 [--name PREFIX] [--report-dir DIR] [--timeout SECONDS]
#   --from               the copied backups: backup.sh archives, RECIPIENTS-*, and pitr-<system id>/{base,wal}
#   --identity           the age identity: yours, 0600 or 0400, outside deploy/ and --from (e.g. on a tmpfs)
#   --retrieval-started  when the custodian started retrieving the recovery key, UTC: 2026-11-05T14:30:00Z
#   --name               prefix of the containers it creates (default: restaurantpro-verify)
#   --report-dir         where the JSON report goes (default: the current directory)
#   --timeout            seconds pitr-restore.sh may take (default: 1800)
# Exits non-zero when any check fails; the report says which.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DB=restaurantpro
DB_USER=restaurantpro
RTO_SECONDS=7200 # spec §11
die() { echo "verify-restore: $*" >&2; exit 1; }
log() { echo "verify-restore: $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=lib/roles.sh
. "$DEPLOY_DIR/lib/roles.sh"

from=""
identity=""
key_recovery=false
retrieval_started=""
prefix=restaurantpro-verify
report_dir="$PWD"
timeout=1800
while (($#)); do
  case "$1" in
    --from) from="${2:?--from needs a value}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a value}"; shift 2 ;;
    --key-recovery) key_recovery=true; shift ;;
    --retrieval-started) retrieval_started="${2:?--retrieval-started needs a value}"; shift 2 ;;
    --name) prefix="${2:?--name needs a value}"; shift 2 ;;
    --report-dir) report_dir="${2:?--report-dir needs a value}"; shift 2 ;;
    --timeout) timeout="${2:?--timeout needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -d "$from" ]] || die "--from: directory not found: $from"
[[ -n "$identity" ]] || die "--identity is required"
[[ -d "$report_dir" ]] || die "--report-dir: directory not found: $report_dir"
[[ "$prefix" =~ ^[a-zA-Z0-9][a-zA-Z0-9_.-]*$ ]] || die "invalid --name: $prefix"
require_identity "$identity" "$from"

epoch_of() { # $1 UTC time YYYY-MM-DDTHH:MM:SSZ
  date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$1" +%s 2>/dev/null || date -u -d "$(sed 's/T/ /; s/Z$//' <<<"$1")" +%s
}
iso_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }
json_str() { printf '"%s"' "$(sed 's/\\/\\\\/g; s/"/\\"/g' <<<"$1" | tr -d '\n')"; }

started="$(iso_now)"
t_start=$SECONDS
mode=weekly
problems=()
problem() { problems+=("$*"); log "PROBLEM: $*"; }
retrieval_epoch=""
if $key_recovery; then
  mode="key-recovery"
  [[ -z "${BACKUP_AGE_IDENTITY:-}" ]] || die "--key-recovery: BACKUP_AGE_IDENTITY is set; the drill uses the recovery identity alone"
  [[ "$(grep -cE "$RP_AGE_SECRET_KEY" "$identity" || true)" == 1 ]] || die "--key-recovery: $identity must hold exactly one identity, the recovery key"
  if [[ -n "$retrieval_started" ]]; then
    [[ "$retrieval_started" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] || die "--retrieval-started must look like 2026-11-05T14:30:00Z"
    retrieval_epoch="$(epoch_of "$retrieval_started")" || die "cannot read --retrieval-started"
  else
    log "WARNING: --retrieval-started not given: the key retrieval is not timed"
  fi
fi

# The key: one of the copied recipients, which match their recorded fingerprint.
key="$(age_public_key "$identity")" || die "cannot read the public key of $identity"
key_label=""
# The current recipients: the newest write-once RECIPIENTS-<UTC>-<fingerprint>; its name must match its keys.
recipients_copy="$(find "$from" -maxdepth 2 -type f -name 'RECIPIENTS-*' 2>/dev/null | awk -F/ '{ print $NF "\t" $0 }' \
  | sort | tail -1 | cut -f2 || true)"
if [[ -z "$recipients_copy" ]]; then
  problem "no RECIPIENTS-<UTC>-<fingerprint> in $from: run verify-backups.sh on the server (it records them), then copy the backups again"
elif [[ "$(recipients_fingerprint "$recipients_copy")" != "${recipients_copy##*-}" ]]; then
  problem "$recipients_copy does not match the fingerprint in its name"
elif recipient_keys "$recipients_copy" | grep -qxF "$key"; then
  key_label="$(grep -B1 -xF "$key" "$recipients_copy" | sed -n 's/^#[[:space:]]*//p' | head -1)"
  log "key $key ($key_label) is a current recipient"
else
  problem "the identity's key $key is not one of the current recipients in $recipients_copy"
fi

# offbox-sync.sh copies <dir>/pitr to pitr-<system identifier>/: take the tree with the newest base backup.
pitr_dir="$(find "$from" -mindepth 3 -maxdepth 3 -type f -path "$from/pitr*/base/restaurantpro-base-*.tar.age" 2>/dev/null \
  | awk -F/ '{ print $NF "\t" $0 }' | sort | tail -1 | cut -f2 || true)"
pitr_dir="${pitr_dir%/base/*}"
pitr_dir="${pitr_dir:-$from/pitr}"
base="$(find "$pitr_dir/base" -maxdepth 1 -type f -name 'restaurantpro-base-*.tar.age' 2>/dev/null | sort | tail -1)"
logical="$(find "$from" -maxdepth 1 -type f -name 'restaurantpro-*.tar.age' 2>/dev/null | sort | tail -1)"
newest_wal="$(find "$pitr_dir/wal" -maxdepth 1 -type f -name '*.age' 2>/dev/null | sed 's|.*/||' | grep -E '^[0-9A-F]{24}\.age$' | sort | tail -1 | cut -c1-24)"
pitr_name="$prefix-pitr"
scratch="$prefix-logical"
role_secrets="$(mktemp -d "${TMPDIR:-/tmp}/rp-verify-roles.XXXXXX")"
cleanup() {
  docker rm -f -v "$pitr_name" "$scratch" >/dev/null 2>&1 || true
  docker volume rm "$pitr_name-data" >/dev/null 2>&1 || true
  rm -rf "$role_secrets"
}
trap cleanup EXIT
in_db() { docker exec "$1" psql -X -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "${3:-$DB}" -c "$2"; }
check_restored() { # $1 container: sets AMCHECK (clean|failed) and FLYWAY (newest successful version, or none)
  if docker exec "$1" pg_amcheck -U "$DB_USER" --all --install-missing >&2; then AMCHECK=clean; else AMCHECK=failed; problem "$1: pg_amcheck --all found problems"; fi
  FLYWAY=none
  [[ "$(in_db "$1" "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")" == t ]] || return 0
  [[ "$(in_db "$1" 'SELECT count(*) FROM public.flyway_schema_history WHERE NOT success')" == 0 ]] || problem "$1: the Flyway history has a failed migration"
  FLYWAY="$(in_db "$1" "SELECT coalesce(max(version), 'none') FROM public.flyway_schema_history WHERE success AND version IS NOT NULL")"
  local roles
  roles="$(roles_problems "$1")" || { problem "$1: the role check could not run"; return 0; }
  # A backup older than ADR 0039 has no roles yet (db-provision creates them at the next up): nothing to check.
  if [[ -n "$roles" && "$roles" != *"role restaurantpro_owner missing"* ]]; then
    problem "$1: database roles not as D163: $(head -5 <<<"$roles" | tr '\n' ';')"
  fi
}

rto="" wal_reached="" pitr_amcheck="" pitr_flyway="" logical_amcheck="" logical_flyway="" logical_tables=""
if [[ -z "$base" ]]; then
  problem "no encrypted base backup in $from/pitr*/base"
else
  log "restoring $(basename "$base") with every copied WAL file (target latest)"
  t0=$SECONDS
  if "$DEPLOY_DIR/pitr-restore.sh" --force --target-time latest --timeout "$timeout" --name "$pitr_name" \
    --wal-dir "$pitr_dir/wal" --identity "$identity" "$base" >/dev/null; then
    rto=$((SECONDS - t0))
    redo="$(docker logs "$pitr_name" 2>&1 | sed -n 's/.*redo done at \([0-9A-F]*\/[0-9A-F]*\).*/\1/p' | tail -1)"
    if [[ -n "$redo" && -n "$newest_wal" ]]; then
      wal_reached="$(printf '%s%08X%08X' "${newest_wal:0:8}" "$((16#${redo%/*}))" "$((16#${redo#*/} >> 24))")"
      [[ ! "$wal_reached" < "$newest_wal" ]] || problem "recovery stopped at $wal_reached, before the newest copied WAL $newest_wal"
    else
      problem "cannot tell how far recovery got (redo LSN '${redo}', newest WAL '${newest_wal}')"
    fi
    check_restored "$pitr_name"
    pitr_amcheck=$AMCHECK pitr_flyway=$FLYWAY
    log "PITR: base $(basename "$base"), WAL reached $wal_reached (newest copied $newest_wal), ${rto}s, amcheck $pitr_amcheck, Flyway $pitr_flyway"
  else
    problem "pitr-restore.sh of $(basename "$base") failed (see above)"
  fi
fi

if [[ -z "$logical" ]]; then
  problem "no encrypted backup.sh archive in $from"
else
  log "restoring $(basename "$logical") into a scratch container"
  POSTGRES_PASSWORD="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')" docker run -d --name "$scratch" --network none \
    -e POSTGRES_PASSWORD -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" --label restaurantpro.verify="$prefix" "$RP_AGE_IMAGE" >/dev/null
  ready=false
  for _ in $(seq 1 60); do
    if docker exec "$scratch" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" 2>/dev/null; then ready=true; break; fi
    sleep 1
  done
  if ! $ready; then
    problem "the scratch PostgreSQL did not start"
  elif make_role_secrets "$role_secrets" \
    && "$DEPLOY_DIR/restore.sh" --container "$scratch" --identity "$identity" --secrets "$role_secrets" "$logical"; then
    logical_tables="$(in_db "$scratch" "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE c.relkind IN ('r', 'p') AND n.nspname NOT IN ('pg_catalog', 'information_schema')")"
    ((logical_tables > 0)) || problem "the logical restore holds no tables"
    check_restored "$scratch"
    logical_amcheck=$AMCHECK logical_flyway=$FLYWAY
    log "logical: $(basename "$logical"), $logical_tables tables, amcheck $logical_amcheck, Flyway $logical_flyway"
  else
    problem "restore.sh of $(basename "$logical") failed (see above)"
  fi
fi

finished="$(iso_now)"
retrieval_seconds=""
if [[ -n "$retrieval_epoch" ]]; then
  retrieval_seconds=$(($(date -u +%s) - retrieval_epoch))
  ((retrieval_seconds <= RTO_SECONDS)) || problem "from the start of the key retrieval to a restored database took ${retrieval_seconds}s, more than the RTO (${RTO_SECONDS}s)"
fi
result=PASS
((${#problems[@]} == 0)) || result=FAIL
report="$report_dir/drill-$(date -u +%Y%m%dT%H%M%SZ).json"
{
  echo "{"
  echo "  \"mode\": $(json_str "$mode"),"
  echo "  \"result\": $(json_str "$result"),"
  echo "  \"started\": $(json_str "$started"),"
  echo "  \"finished\": $(json_str "$finished"),"
  echo "  \"total_seconds\": $((SECONDS - t_start)),"
  echo "  \"key\": $(json_str "$key"),"
  echo "  \"key_label\": $(json_str "$key_label"),"
  echo "  \"retrieval_started\": $(json_str "$retrieval_started"),"
  echo "  \"retrieval_to_restored_seconds\": ${retrieval_seconds:-null},"
  echo "  \"base\": $(json_str "$(basename "${base:-}")"),"
  echo "  \"wal_newest\": $(json_str "$newest_wal"),"
  echo "  \"wal_reached\": $(json_str "$wal_reached"),"
  echo "  \"rto_seconds\": ${rto:-null},"
  echo "  \"pitr_amcheck\": $(json_str "$pitr_amcheck"),"
  echo "  \"pitr_flyway_version\": $(json_str "$pitr_flyway"),"
  echo "  \"logical\": $(json_str "$(basename "${logical:-}")"),"
  echo "  \"logical_tables\": ${logical_tables:-null},"
  echo "  \"logical_amcheck\": $(json_str "$logical_amcheck"),"
  echo "  \"logical_flyway_version\": $(json_str "$logical_flyway"),"
  printf '  "problems": ['
  sep=""
  for p in ${problems[@]+"${problems[@]}"}; do printf '%s%s' "$sep" "$(json_str "$p")"; sep=", "; done
  echo "]"
  echo "}"
} >"$report"
log "$result: report $report"
[[ "$result" == PASS ]]
