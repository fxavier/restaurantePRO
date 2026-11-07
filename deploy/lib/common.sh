# shellcheck shell=bash
# Rules shared by the backup scripts (D98). Sourced, never run. The caller defines die() and DEPLOY_DIR.
# Bash 3.2 compatible (macOS); also sourced by postgres/preflight.sh inside the postgres container.

RP_X25519_RECIPIENT='^age1[02-9ac-hj-np-z]{58}$'
# A whole age identity, so that a mention of the prefix in a comment or the README is not a hit.
RP_AGE_SECRET_KEY='AGE-SECRET-KEY-1[02-9AC-HJ-NP-Z]{58}'
# The only plaintext files allowed among the backups, apart from well-formed .sha256 sidecars and the
# write-once RECIPIENTS-<UTC>-<fingerprint> copies. RECIPIENTS_SHA256, LAST_OFFBOX_SYNC and ARCHIVER_FAILED_COUNT
# change in place, so they never leave the server (offbox-sync.sh; the destination is write-once).
RP_MARKERS=" SYSTEM_IDENTIFIER RECIPIENTS_SHA256 LAST_OFFBOX_SYNC ARCHIVER_FAILED_COUNT "
# shellcheck disable=SC2034 # used by offbox-sync.sh
RP_LOCAL_MARKERS="RECIPIENTS_SHA256 LAST_OFFBOX_SYNC ARCHIVER_FAILED_COUNT"
RP_PARTIAL="" # temporary files of put_file/seal in progress; the caller's EXIT trap runs remove_partials
RP_CIPHER=""
remove_partials() { rm -f ${RP_PARTIAL:+"$RP_PARTIAL"} ${RP_CIPHER:+"$RP_CIPHER"}; }

# deploy/.env decides the mode: it must not be writable by group or others, nor owned by anyone but root or
# the effective uid (checked always, before anything in it is trusted).
require_safe_dotenv() {
  local f="$DEPLOY_DIR/.env"
  [[ -e "$f" ]] || return 0
  [[ -f "$f" && ! -L "$f" ]] || die "$f is not a regular file"
  ((8#$(file_mode "$f") & 8#022)) && die "$f is writable by group or others (mode $(file_mode "$f")): it sets RESTAURANTPRO_ENVIRONMENT. Fix: chmod 600 '$f'"
  [[ "$(file_uid "$f")" == 0 || "$(file_uid "$f")" == "$(id -u)" ]] \
    || die "$f is owned by uid $(file_uid "$f"), not by root or uid $(id -u): it sets RESTAURANTPRO_ENVIRONMENT. Fix: chown $(id -u) '$f'"
  return 0
}

# $1 name: the process environment, else deploy/.env (read with grep, never sourced), else empty.
setting() {
  local name=$1
  if [[ -n "${!name:-}" ]]; then printf '%s' "${!name}"; return 0; fi
  [[ -f "$DEPLOY_DIR/.env" ]] || return 0
  require_safe_dotenv
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

# The Compose project of this installation, as docker compose resolves it for deploy/compose.yaml:
# COMPOSE_PROJECT_NAME from the environment, else from the env files (COMPOSE_ENV_FILES, comma-separated, else
# deploy/.env; the last one that sets it wins), else the top-level name: of compose.yaml.
installation_project() {
  local files f found="" name
  if [[ -n "${COMPOSE_PROJECT_NAME:-}" ]]; then printf '%s\n' "$COMPOSE_PROJECT_NAME"; return 0; fi
  files="${COMPOSE_ENV_FILES:-$DEPLOY_DIR/.env}"
  local IFS=,
  for f in $files; do
    [[ "$f" == /* ]] || f="$DEPLOY_DIR/$f"
    [[ -f "$f" ]] || continue
    [[ "$f" != "$DEPLOY_DIR/.env" ]] || require_safe_dotenv
    name="$(grep -E '^[[:space:]]*COMPOSE_PROJECT_NAME=' "$f" | tail -1 | sed -E "s/^[[:space:]]*COMPOSE_PROJECT_NAME=//; s/^[\"']//; s/[\"'][[:space:]]*$//" || true)"
    [[ -z "$name" ]] || found="$name"
  done
  [[ -n "$found" ]] || found="$(sed -nE 's/^name:[[:space:]]*([^[:space:]#]+).*/\1/p' "$DEPLOY_DIR/compose.yaml" | head -1)"
  printf '%s\n' "$found"
}

# Refuses database container $1 when Docker Compose manages it for another installation than this one
# (installation_project): a restore or a backup must never reach another company's database by a mistyped
# --container. $2 = the project named by --other-installation, the only way past this guard, and only for
# exactly that project (logged as a WARNING). A container without a Compose label (a throwaway or a
# pitr-restore.sh container) is allowed.
require_own_installation() {
  local container=$1 other=${2:-} project own
  project="$(docker container inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' "$container" 2>/dev/null)" \
    || die "container $container not found"
  own="$(installation_project)"
  if [[ -n "$other" ]]; then
    [[ "$project" == "$other" && "$project" != "$own" ]] \
      || die "--other-installation $other does not match container $container, which belongs to installation ${project:-<none: not Compose-managed>} (this installation: $own); refusing to touch it"
    echo "${0##*/}: WARNING: --other-installation $other: acting on container $container of ANOTHER installation ($other), not this one ($own)" >&2
    return 0
  fi
  [[ -z "$project" || "$project" == "$own" ]] \
    || die "container $container belongs to installation $project (Compose project), not to this installation ($own); refusing to touch it. If you really mean installation $project, pass --other-installation $project"
}

# D98 rule 1: production unless RESTAURANTPRO_ENVIRONMENT says test. Sets RP_ENV.
env_mode() {
  require_safe_dotenv # here too: a die inside setting's command substitution would only end the subshell
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
is_age_file() { [[ "$(head -c 22 "$1" 2>/dev/null | tr -d '\000' | tr '\n' '|')" == "age-encryption.org/v1|" ]]; }

# Sidecar <x>.sha256 of <x>.age: exactly two lines, "<plaintext sha256>  <x>" and "<ciphertext sha256>  <x>.age".
# The ciphertext line lets key-free checks catch bit rot or a swapped file.
is_sidecar() {
  local f=$1 name
  [[ "$f" == *.sha256 && -f "$f" && ! -L "$f" ]] || return 1
  (($(wc -c <"$f") <= 1024)) || return 1
  name="$(basename "${f%.sha256}")"
  [[ "$(wc -l <"$f" | tr -d ' ')" == 2 ]] || return 1
  [[ "$(sed -n 1p "$f")" =~ ^[0-9a-f]{64}\ \ (.*)$ && "${BASH_REMATCH[1]}" == "$name" ]] || return 1
  [[ "$(sed -n 2p "$f")" =~ ^[0-9a-f]{64}\ \ (.*)$ && "${BASH_REMATCH[1]}" == "$name.age" ]]
}
sidecar_plain() { sed -n 1p "$1" | cut -d' ' -f1; }
sidecar_cipher() { sed -n 2p "$1" | cut -d' ' -f1; }
is_marker() {
  local b
  b="$(basename "$1")"
  [[ "$RP_MARKERS" == *" $b "* || "$b" =~ ^RECIPIENTS-[0-9]{8}T[0-9]{6}Z-[0-9a-f]{64}$ ]] || is_sidecar "$1"
}
# Prints what is wrong with $1 as a file among the backups (nothing when it is fine): it must be a marker, a
# sidecar, or age ciphertext whose sidecar exists and holds its SHA-256. Key-free.
backup_file_problem() {
  local f=$1 sidecar
  if is_marker "$f"; then return 0; fi
  if [[ "$f" == *.sha256 ]]; then echo "malformed sidecar: $f"; return 0; fi
  if ! is_age_file "$f"; then echo "not age ciphertext: $f"; return 0; fi
  sidecar="${f%.age}.sha256"
  if ! is_sidecar "$sidecar"; then echo "$f has no well-formed ${sidecar##*/} sidecar"; return 0; fi
  [[ "$(sha256_hex "$f")" == "$(sidecar_cipher "$sidecar")" ]] \
    || echo "$f does not match the ciphertext SHA-256 in its sidecar (bit rot, or a swapped file)"
}

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

# The recipients' fingerprint: SHA-256 of their sorted distinct keys (comments do not count).
recipients_fingerprint() { recipient_keys "$1" | sha256_hex /dev/stdin; }
# Records the recipients of $2 as the accepted ones for backup directory $1: RECIPIENTS_SHA256 (stays on the
# server) and RECIPIENTS-<UTC>-<fingerprint>, a write-once copy the off-box copy carries to the restore host,
# where the newest name holds the current recipients (a rotation back to older keys gets a new name).
recipients_copy() { find "$1" -maxdepth 1 -type f -name "RECIPIENTS-*-$2" 2>/dev/null | head -1 || true; }
record_recipients() {
  local dir=$1 fp old keep
  fp="$(recipients_fingerprint "$2")"
  keep="$(recipients_copy "$dir" "$fp")"
  for old in "$dir"/RECIPIENTS-*; do [[ ! -f "$old" || "$old" == "$keep" ]] || rm -f -- "$old"; done
  [[ -n "$keep" ]] || put_file "$2" "$dir/RECIPIENTS-$(date -u +%Y%m%dT%H%M%SZ)-$fp"
  printf '%s\n' "$fp" | put_file /dev/stdin "$dir/RECIPIENTS_SHA256"
}
# A .age file under $1 that no record covers: subdirectories with their own RECIPIENTS_SHA256 (basebackup.sh's
# <dir>/pitr inside backup.sh's <dir>) answer for themselves.
unrecorded_ciphertext() {
  find "$1" -mindepth 1 -type d -exec test -e '{}/RECIPIENTS_SHA256' \; -prune -o -type f -name '*.age' -print 2>/dev/null \
    | head -1 || true
}
# D98 rule 2, production backups: refuses when the recipients of $2 are not the ones recorded for $1, or when
# $1 holds ciphertext but no record (only an empty installation records silently). Prints "record" when the
# caller should record them once it writes.
require_recorded_recipients() {
  local dir=$1 fp known
  fp="$(recipients_fingerprint "$2")"
  known="$(cat "$dir/RECIPIENTS_SHA256" 2>/dev/null || true)"
  if [[ -z "$known" ]]; then
    [[ -z "$(unrecorded_ciphertext "$dir")" ]] \
      || die "$dir holds encrypted backups but no RECIPIENTS_SHA256: the recipients cannot be checked. If $2 holds the right keys, run ./verify-backups.sh --accept-recipients-change"
    echo record
  elif [[ "$known" != "$fp" ]]; then
    die "the recipients changed since they were recorded for $dir ($known -> $fp). If you rotated a key on purpose, run ./verify-backups.sh --accept-recipients-change; otherwise someone may have swapped in their own key. Nothing was written"
  fi
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

# D98 rule 3: writes plaintext $1 as $2.age encrypted to recipients file $3, with its sidecar $2.sha256
# (is_sidecar) renamed into place first, so a complete .age always has its sidecar. Without $3 (test mode only)
# writes $2 as is. $1 may be $2 itself (--encrypt-legacy): nothing is written under either name directly.
seal() {
  local plain=$1 final=$2 recipients=${3:-} name
  if [[ -z "$recipients" ]]; then put_file "$plain" "$final"; return; fi
  name="$(basename "$final")"
  RP_CIPHER="$(mktemp "$final.age.tmp.XXXXXX")"
  age_encrypt "$recipients" <"$plain" >"$RP_CIPHER" || die "age could not encrypt $final"
  sync "$RP_CIPHER"
  printf '%s  %s\n%s  %s\n' "$(sha256_hex "$plain")" "$name" "$(sha256_hex "$RP_CIPHER")" "$name.age" | put_file /dev/stdin "$final.sha256"
  RP_PARTIAL="$RP_CIPHER"
  RP_CIPHER=""
  commit_partial "$final.age"
}
