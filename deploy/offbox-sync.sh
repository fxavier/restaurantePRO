#!/usr/bin/env bash
# The only off-box copier of the backups (D98 rule 4, spec §10, §11). Copies <dir> (backup.sh archives and
# basebackup.sh's pitr/) with rsync, over ssh to a destination that can neither be read nor deleted from
# this server, or to a local removable disk. Then writes <dir>/LAST_OFFBOX_SYNC, which verify-backups.sh
# checks. Run it right after every basebackup.sh --wal-only (deploy/README.md, "Backup encryption").
#
# ssh: BatchMode, StrictHostKeyChecking=yes against the pinned deploy/offbox_known_hosts only, key
#      authentication only. The destination's authorized_keys forces rrsync -wo -no-del (README).
# In production it refuses, copying nothing: any file in <dir> that is not age ciphertext (apart from the
# markers and the .sha256 sidecars), an age identity on the server, loose modes or owners, and a local
# target on a network filesystem (findmnt FSTYPE nfs*, cifs, smb*).
# Sidecars and markers go first, the ciphertext after, so a copied <f>.age always has its sidecar.
#
# Usage: deploy/offbox-sync.sh [--check-append-only] [--dir DIR] [--target TARGET] [--port N]
#                              [--ssh-key FILE] [--known-hosts FILE]
#   --target             [user@]host:path for ssh, or /absolute/path on a removable disk
#                        (default: $OFFBOX_TARGET, from the environment or deploy/.env)
#   --dir                what to copy (default: $BACKUP_DIR or deploy/backups)
#   --port               ssh port (default: $OFFBOX_SSH_PORT or 22)
#   --ssh-key            ssh private key for the destination (default: $OFFBOX_SSH_KEY, else ssh's default)
#   --known-hosts        the pinned host key (default: deploy/offbox_known_hosts)
#   --check-append-only  also prove the destination is write-once: a canary is written under a new name, then
#                        reading it back, deleting it and overwriting it must all be refused. Skipped for a
#                        local disk.
# Exits non-zero on any failure.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
die() { echo "offbox-sync: $*" >&2; exit 1; }
log() { echo "offbox-sync: $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"

dir="${BACKUP_DIR:-$DEPLOY_DIR/backups}"
target=""
port=""
key=""
known_hosts="$DEPLOY_DIR/offbox_known_hosts"
check=false
while (($#)); do
  case "$1" in
    --check-append-only) check=true; shift ;;
    --dir) dir="${2:?--dir needs a value}"; shift 2 ;;
    --target) target="${2:?--target needs a value}"; shift 2 ;;
    --port) port="${2:?--port needs a value}"; shift 2 ;;
    --ssh-key) key="${2:?--ssh-key needs a value}"; shift 2 ;;
    --known-hosts) known_hosts="${2:?--known-hosts needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done
env_mode
target="${target:-$(setting OFFBOX_TARGET)}"
port="${port:-$(setting OFFBOX_SSH_PORT)}"
port="${port:-22}"
key="${key:-$(setting OFFBOX_SSH_KEY)}"
[[ -n "$target" ]] || die "no destination: set OFFBOX_TARGET in deploy/.env or pass --target"
[[ "$port" =~ ^[1-9][0-9]*$ ]] || die "--port must be a port number"
[[ -d "$dir" ]] || die "backup directory not found: $dir"
local_target=false
[[ "$target" != /* ]] || local_target=true

fs_type() { # $1 path: its filesystem type
  if command -v findmnt >/dev/null; then findmnt -n -o FSTYPE -T "$1"; return; fi
  local dev
  dev="$(df -P "$1" | awk 'NR == 2 { print $1 }')" # macOS (not a supported server; development only)
  mount | awk -v d="$dev" '$1 == d { sub(/^.*\(/, ""); sub(/,.*$/, ""); print; exit }'
}

if production; then
  refuse_identity_on_server "$dir"
  require_private_dir "$dir"
  plain="$(find "$dir" -path "$dir/.lock" -prune -o -type f ! -name '*.tmp.*' ! -name '*.tmp' -print | while IFS= read -r f; do
    if ! is_marker "$f" && ! is_age_file "$f"; then echo "$f"; fi
  done | head -3 || true)"
  [[ -z "$plain" ]] || die "not age ciphertext, so never copied off the box (D98):
$plain
  Move it out of $dir (logs belong in deploy/logs/), or run ./basebackup.sh --encrypt-legacy for S27 backups"
  if $local_target; then
    [[ -d "$target" ]] || die "local target not found (is the disk mounted?): $target"
    case "$(fs_type "$target")" in
      nfs* | cifs | smb*) die "$target is on a network filesystem ($(fs_type "$target")): use ssh with rrsync (README)" ;;
    esac
  else
    [[ -f "$known_hosts" ]] || die "no pinned host key: $known_hosts (README: ssh-keyscan once, check the fingerprint)"
    ((8#$(file_mode "$known_hosts") & 8#022)) && die "$known_hosts is writable by group or others. Fix: chmod 644 '$known_hosts'"
    if [[ -n "$key" ]]; then require_private_file "$key"; fi
  fi
elif ! $local_target; then
  [[ -f "$known_hosts" ]] || die "no pinned host key: $known_hosts"
fi

ssh_cmd=(ssh -F /dev/null -p "$port" -o BatchMode=yes -o StrictHostKeyChecking=yes -o "UserKnownHostsFile=$known_hosts"
  -o GlobalKnownHostsFile=/dev/null -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no
  -o PubkeyAuthentication=yes -o ForwardAgent=no -o ClearAllForwardings=yes -o ConnectTimeout=20)
if [[ -n "$key" ]]; then ssh_cmd+=(-i "$key" -o IdentitiesOnly=yes); fi
rsync_to() { # rsync arguments; the destination is the last one
  if $local_target; then rsync "$@"; else rsync -e "${ssh_cmd[*]}" "$@"; fi
}
dest="${target%/}/"
[[ "$target" != *: ]] || dest="$target" # host: (the rrsync root)
# Write-once: never replace a file already at the destination (the destination enforces it too, with
# rrsync -no-overwrite), and never send the markers that change in place.
excludes=(--ignore-existing --exclude=.lock --exclude='*.tmp.*' --exclude='*.tmp' --exclude='.work.*')
for m in $RP_LOCAL_MARKERS; do excludes+=(--exclude="$m"); done
push() { # $1 local directory, $2 destination directory, $3... more rsync options: sidecars and markers first
  rsync_to -rt "${excludes[@]}" "${@:3}" --include='*/' --include='*.sha256' --include='SYSTEM_IDENTIFIER' \
    --include='RECIPIENTS-*' --exclude='*' "$1/" "$2" || die "could not copy the sidecars of $1 to $target"
  rsync_to -rt "${excludes[@]}" "${@:3}" "$1/" "$2" || die "could not copy $1 to $target"
}

log "copying $dir to $target"
# basebackup.sh's directory goes to pitr-<system identifier>/: a new cluster restarts the WAL names, and the
# write-once destination would otherwise keep the old cluster's files under the new cluster's names.
push "$dir" "$dest" --exclude=/pitr
if [[ -d "$dir/pitr" ]]; then
  sysid="$(cat "$dir/pitr/SYSTEM_IDENTIFIER" 2>/dev/null || true)"
  [[ "$sysid" =~ ^[0-9]+$ ]] || die "$dir/pitr has no SYSTEM_IDENTIFIER: run ./basebackup.sh first"
  push "$dir/pitr" "${dest}pitr-$sysid/"
fi

if $check; then
  if $local_target; then
    log "--check-append-only skipped: $target is a local disk (README: removable disks cannot prove it)"
  else
    work="$(mktemp -d "${TMPDIR:-/tmp}/rp-offbox.XXXXXX")"
    trap 'rm -rf "$work"' EXIT
    mkdir "$work/canary" "$work/other" "$work/empty"
    canary="canary-$(date -u +%Y%m%dT%H%M%SZ)-$$-$RANDOM" # a new name: the destination never overwrites
    date -u +%Y-%m-%dT%H:%M:%SZ >"$work/canary/$canary"
    printf 'a different, longer content for the overwrite attempt %s\n' "$RANDOM" >"$work/other/$canary"
    touch -t 200001010000 "$work/other/$canary"
    needs='authorized_keys needs command="rrsync -wo -no-del -no-overwrite <dir>"'
    rsync_to -rt "$work/canary/" "${dest}.offbox-canary/" || die "could not write the canary to $target"
    if rsync_to "${dest}.offbox-canary/$canary" "$work/read-back" 2>/dev/null; then
      die "the destination is not write-once: this server could read the canary back ($needs)"
    fi
    if rsync_to -r --delete "$work/empty/" "${dest}.offbox-canary/" 2>/dev/null; then
      die "the destination is not write-once: this server could delete the canary ($needs)"
    fi
    # Without --ignore-existing here: the destination itself must skip the file (-no-overwrite), which a
    # write-only client can only see in the itemized changes.
    changes="$(rsync_to -rti "$work/other/" "${dest}.offbox-canary/" 2>/dev/null || true)"
    if [[ "$changes" == *"$canary"* ]]; then
      die "the destination is not write-once: this server could overwrite the canary ($needs)"
    fi
    log "the destination is write-once: the canary was written, and reading, deleting and overwriting it were refused"
  fi
fi

printf '%s %s\n' "$(date -u +%s)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"${TMPDIR:-/tmp}/rp-offbox-stamp.$$"
put_file "${TMPDIR:-/tmp}/rp-offbox-stamp.$$" "$dir/LAST_OFFBOX_SYNC"
rm -f "${TMPDIR:-/tmp}/rp-offbox-stamp.$$"
log "done"
