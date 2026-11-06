# shellcheck shell=bash
# Rules shared by the backup scripts (D98). Sourced, never run. The caller defines die() and DEPLOY_DIR.
# Bash 3.2 compatible (macOS); also sourced by postgres/preflight.sh inside the postgres container.

RP_X25519_RECIPIENT='^age1[02-9ac-hj-np-z]{58}$'
# A whole age identity, so that a mention of the prefix in a comment or the README is not a hit.
RP_AGE_SECRET_KEY='AGE-SECRET-KEY-1[02-9AC-HJ-NP-Z]{58}'
# The only plaintext files allowed among the backups, apart from the .sha256 sidecars.
RP_MARKERS=" SYSTEM_IDENTIFIER RECIPIENTS RECIPIENTS_SHA256 LAST_OFFBOX_SYNC ARCHIVER_FAILED_COUNT "
RP_PARTIAL="" # a temporary file of put_file/seal in progress; the caller's EXIT trap removes it

# $1 name: the process environment, else deploy/.env (read with grep, never sourced), else empty.
setting() {
  local name=$1
  if [[ -n "${!name:-}" ]]; then printf '%s' "${!name}"; return 0; fi
  [[ -f "$DEPLOY_DIR/.env" ]] || return 0
  grep -E "^[[:space:]]*$name=" "$DEPLOY_DIR/.env" | tail -1 \
    | sed -E "s/^[[:space:]]*$name=//; s/^[\"']//; s/[\"'][[:space:]]*$//" || true
}

# The recipients file: BACKUP_AGE_RECIPIENTS_FILE (relative to deploy/, as compose.yaml reads it), else
# deploy/backup-recipients.txt.
recipients_file() {
  local f
  f="$(setting BACKUP_AGE_RECIPIENTS_FILE)"
  f="${f:-backup-recipients.txt}"
  [[ "$f" == /* ]] || f="$DEPLOY_DIR/${f#./}"
  printf '%s\n' "$f"
}

# D98 rule 1: production unless RESTAURANTPRO_ENVIRONMENT says test. Sets RP_ENV.
env_mode() {
  RP_ENV="$(setting RESTAURANTPRO_ENVIRONMENT)"
  RP_ENV="${RP_ENV:-production}"
  case "$RP_ENV" in
    production | test) ;;
    *) die "RESTAURANTPRO_ENVIRONMENT must be production or test, got: $RP_ENV" ;;
  esac
}
production() { [[ "$RP_ENV" == production ]]; }

file_mode() { stat -c %a "$1" 2>/dev/null || stat -f %Lp "$1"; } # GNU/busybox, else BSD
file_uid() { stat -c %u "$1" 2>/dev/null || stat -f %u "$1"; }
abs_path() { (cd "$(dirname "$1")" && printf '%s/%s\n' "$(pwd -P)" "$(basename "$1")"); }
abs_dir() { (cd "$1" && pwd -P); }
sha256_hex() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }
is_marker() { [[ "$RP_MARKERS" == *" $(basename "$1") "* || "$1" == *.sha256 ]]; }
is_age_file() { [[ "$(head -c 22 "$1" 2>/dev/null | tr -d '\000' | tr '\n' '|')" == "age-encryption.org/v1|" ]]; }

# D98 rule 5: $1 and everything under it owned by the effective uid, directories 0700, files 0600.
# Refuses rather than fixes; the message says what to run.
require_private_dir() {
  local me bad
  me="$(id -u)"
  [[ -d "$1" && ! -L "$1" ]] || die "$1 is not a directory"
  bad="$(find "$1" ! -uid "$me" -o \( -type d ! -perm 700 \) -o \( -type f ! -perm 600 \) -o \( ! -type d ! -type f \) 2>/dev/null | head -1 || true)"
  [[ -n "$bad" ]] || return 0
  if [[ "$(file_uid "$bad")" != "$me" ]]; then
    die "$bad is owned by uid $(file_uid "$bad"), not by uid $me who runs the backups. Fix: chown -R $me '$1'"
  elif [[ -d "$bad" ]]; then
    die "$bad has mode $(file_mode "$bad"); backup directories must be 0700. Fix: find '$1' -type d -exec chmod 700 {} +"
  elif [[ -f "$bad" ]]; then
    die "$bad has mode $(file_mode "$bad"); backup files must be 0600. Fix: find '$1' -type f -exec chmod 600 {} +"
  fi
  die "$bad is neither a regular file nor a directory (a symlink?); move it out of $1"
}

# $1 file that must be private to the caller: owner = effective uid, mode 0600 or 0400.
require_private_file() {
  [[ -f "$1" && ! -L "$1" ]] || die "not a regular file: $1"
  [[ "$(file_uid "$1")" == "$(id -u)" ]] || die "$1 is owned by uid $(file_uid "$1"), not by you (uid $(id -u)). Fix: chown $(id -u) '$1'"
  case "$(file_mode "$1")" in
    600 | 400) ;;
    *) die "$1 has mode $(file_mode "$1"); it must be 0600 or 0400. Fix: chmod 600 '$1'" ;;
  esac
}

# D98 rule 2, restores: $1 identity file; private, and outside deploy/ and every further directory ($2...).
require_identity() {
  local id d
  require_private_file "$1"
  id="$(abs_path "$1")"
  for d in "$DEPLOY_DIR" "$@"; do
    [[ "$d" == "$1" || ! -d "$d" ]] && continue
    d="$(abs_dir "$d")"
    [[ "$id" != "$d/"* ]] || die "the identity $1 is inside $d: keep it outside deploy/ and the backups (e.g. on a tmpfs)"
  done
}

# The distinct recipients of an age -R file, sorted; comments and blank lines ignored.
recipient_keys() { sed -e 's/#.*//' -e 's/[[:space:]]//g' "$1" | grep -v '^$' | sort -u || true; }

# D98 rules 2 and 5. Prints why $1 is not a usable recipients file, nothing when it is. $2 = minimum number of
# distinct X25519 recipients (production: 2). $3 = "no-owner" inside the container, where host uids mean nothing.
recipients_problem() {
  local f=$1 min=$2 mode bad count
  if [[ ! -f "$f" ]]; then echo "no recipients file at $f"; return; fi
  mode="$(file_mode "$f")"
  if ((8#$mode & 8#133)); then
    echo "$f has mode $mode: it must be 0644 or stricter and not writable by group or others. Fix: chmod 644 '$f'"; return
  fi
  if [[ "${3:-}" != no-owner && "$(file_uid "$f")" != 0 && "$(file_uid "$f")" != "$(id -u)" ]]; then
    echo "$f is owned by uid $(file_uid "$f"): it must be owned by root or by uid $(id -u) who runs the backups. Fix: chown $(id -u) '$f'"; return
  fi
  bad="$(recipient_keys "$f" | grep -vE "$RP_X25519_RECIPIENT" | head -1 || true)"
  if [[ -n "$bad" ]]; then echo "$f holds a line that is not an age X25519 recipient (age1...): ${bad:0:20}..."; return; fi
  count="$(recipient_keys "$f" | wc -l | tr -d ' ')"
  if ((count < min)); then
    echo "$f holds $count distinct recipient(s); production needs at least $min: the operational key and the recovery key (deploy/README.md, \"Backup encryption\")"
  fi
}
require_safe_recipients() { # $1 file, $2 minimum
  local problem
  problem="$(recipients_problem "$1" "$2")"
  [[ -z "$problem" ]] || die "$problem"
}

# D98 rule 2: no age identity on the server. $@ = further directories to scan (the backup directories).
refuse_identity_on_server() {
  local hit d
  [[ -z "${BACKUP_AGE_IDENTITY:-}" ]] \
    || die "BACKUP_AGE_IDENTITY is set: age identities (private keys) never belong on the server (D98). Unset it; restores run on the restore host with --identity"
  for d in "$DEPLOY_DIR" "$@"; do
    [[ -d "$d" ]] || continue
    hit="$(find "$d" -type f -size -65k -exec grep -lE "$RP_AGE_SECRET_KEY" {} + 2>/dev/null | head -1 || true)"
    [[ -z "$hit" ]] || die "$hit holds an age identity (private key). Identities never belong on the server (D98): move it to its custodian, then shred it here"
  done
}

# D98 rule 5: the scripts that handle backups must not be writable by group or others.
require_safe_scripts() {
  local f
  for f in "$DEPLOY_DIR"/*.sh "$DEPLOY_DIR"/lib/*.sh "$DEPLOY_DIR"/postgres/*.sh; do
    [[ -f "$f" ]] || continue
    ((8#$(file_mode "$f") & 8#022)) && die "$f is writable by group or others (mode $(file_mode "$f")). Fix: chmod go-w '$f'"
  done
  return 0
}

# Flushes the temporary file $RP_PARTIAL and renames it to $1: $1 appears complete or not at all.
commit_partial() {
  sync "$RP_PARTIAL" # coreutils/busybox: fsync of the file; macOS: a global sync
  mv "$RP_PARTIAL" "$1"
  RP_PARTIAL=""
  sync "$(dirname "$1")"
}

# Copies $1 to $2 through a temporary file next to it (commit_partial).
put_file() {
  RP_PARTIAL="$(mktemp "$2.tmp.XXXXXX")"
  cat "$1" >"$RP_PARTIAL"
  commit_partial "$2"
}

# D98 rule 3: writes plaintext $1 as $2.age encrypted to recipients file $3, after its sidecar $2.sha256 (the
# plaintext's SHA-256), so a complete .age always has its sidecar. Without $3 (test mode only) writes $2 as is.
# $1 may be $2 itself (basebackup.sh --encrypt-legacy): nothing here is written under either name directly.
seal() {
  local plain=$1 final=$2 recipients=${3:-}
  if [[ -z "$recipients" ]]; then put_file "$plain" "$final"; return; fi
  printf '%s  %s\n' "$(sha256_hex "$plain")" "$(basename "$final")" | put_file /dev/stdin "$final.sha256"
  RP_PARTIAL="$(mktemp "$final.age.tmp.XXXXXX")"
  age_encrypt "$recipients" <"$plain" >"$RP_PARTIAL" || die "age could not encrypt $final"
  commit_partial "$final.age"
}
