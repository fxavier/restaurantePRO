#!/usr/bin/env bash
# Break-glass second-factor reset (D143): the last administrator lost the phone and the recovery codes. Run on the
# server, by whoever administers it (it needs a shell and Docker, the infrastructure administrator's separate risk,
# spec §10). In ONE psql transaction it runs
#   identity.mfa_break_glass_reset(<username>, <reason>)   revokes the account's ACTIVE second factor and drops a
#                                                          pending enrolment and any open enrolment code (V3013)
#   shared.end_account_sessions(<account id>)              ends every session of the account (V1007)
#   audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', <account id>, <reason>)
#                                                          one audit row by the system, details {"via":"console"} (V1105)
# so the factor is revoked, the sessions end and it is audited, or nothing happens. Each module's schema is written
# only by its own function (D6); none of the three is executable by PUBLIC.
# The account then logs in without a factor and enrols again (from the LAN, or with an administrator's code).
# The username and the reason travel as psql variables, never inside the SQL text. The script reads and prints no
# secret: only the account id that was reset.
#
# Usage: deploy/mfa-break-glass.sh [--container NAME] [--other-installation PROJECT] <username> <reason>
#        deploy/mfa-break-glass.sh --self-test   shellcheck, then the argument checks and the exact psql
#                                                 transaction against a stand-in docker (no database touched)
#   --container  the PostgreSQL container (default: this installation's Compose service "postgres")
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
DB=restaurantpro
DB_USER=restaurantpro
die() { echo "mfa-break-glass: $*" >&2; exit 1; }
log() { echo "mfa-break-glass: $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"

# The transaction; :'username' and :'reason' are quoted literals of the psql variables (no SQL injection).
SQL_TRANSACTION="\\set ON_ERROR_STOP on
BEGIN;
SELECT identity.mfa_break_glass_reset(:'username', :'reason') AS account_id \\gset
SELECT shared.end_account_sessions(:'account_id') AS sessions_ended \\gset
SELECT audit.record_console_event('MFA_BREAK_GLASS_RESET', 'UserAccount', :'account_id', :'reason') AS audit_id \\gset
COMMIT;
\\echo :account_id"

break_glass() {
  local container="" other="" username reason out
  while (($#)); do
    case "$1" in
      --container) container="${2:?--container needs a value}"; shift 2 ;;
      --other-installation) other="${2:?--other-installation needs a value}"; shift 2 ;;
      -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
      --) shift; break ;;
      -*) die "unknown option: $1" ;;
      *) break ;;
    esac
  done
  (($# == 2)) || die "usage: mfa-break-glass.sh [--container NAME] <username> <reason> (both are required)"
  username="$1"
  reason="$2"
  [[ -n "${username//[[:space:]]/}" ]] || die "a username is required"
  [[ -n "${reason//[[:space:]]/}" ]] || die "a reason is required: it is kept in the audit trail"
  ((${#reason} <= 500)) || die "the reason is longer than 500 characters"
  if [[ -z "$container" ]]; then
    container="$(docker compose -f "$DEPLOY_DIR/compose.yaml" ps -q postgres 2>/dev/null || true)"
    [[ -n "$container" ]] || die "the postgres service of this installation is not running; pass --container"
  fi
  require_own_installation "$container" "$other"
  out="$(printf '%s\n' "$SQL_TRANSACTION" | docker exec -i "$container" psql -X -q -t -A -U "$DB_USER" -d "$DB" \
    -v ON_ERROR_STOP=1 -v username="$username" -v reason="$reason")" \
    || die "nothing was changed (the transaction rolled back): see the PostgreSQL message above"
  out="$(tail -1 <<<"$out")"
  [[ "$out" =~ ^[0-9a-f-]{36}$ ]] || die "unexpected answer from the database; check the audit trail"
  log "the second factor of account $out is revoked, its sessions ended, and the reset audited (MFA_BREAK_GLASS_RESET)."
  log "It now logs in without a factor and enrols again."
}

# --- self-test: no Docker, no database ---

self_test() {
  local problems=0 work fake out status
  command -v shellcheck >/dev/null || { echo "FAIL: self-test: shellcheck is not installed" >&2; return 1; }
  if (cd "$DEPLOY_DIR" && shellcheck -x mfa-break-glass.sh); then
    echo "PASS: self-test: shellcheck clean" >&2
  else
    echo "FAIL: self-test: shellcheck" >&2; return 1
  fi
  work="$(mktemp -d)"
  # shellcheck disable=SC2064 # expand now: $work is fixed
  trap "rm -rf '$work'" RETURN
  fake="$work/bin"
  mkdir -p "$fake"
  # A stand-in docker: the compose lookup finds "fakepg" (not Compose-managed), exec records its arguments and its
  # stdin and answers like psql with an account id.
  cat >"$fake/docker" <<'FAKE'
#!/usr/bin/env bash
case "$1" in
  compose) echo fakepg ;;
  container) echo '|' ;;
  exec)
    printf '%s\n' "$@" >"$RP_SELFTEST_DIR/args"
    cat >"$RP_SELFTEST_DIR/stdin"
    echo 0190b000-0000-7000-8000-000000000042 ;;
  *) exit 1 ;;
esac
FAKE
  chmod 700 "$fake/docker"
  run() { PATH="$fake:$PATH" RP_SELFTEST_DIR="$work" COMPOSE_PROJECT_NAME=selftest "$0" "$@" 2>&1; }
  expect_refusal() { # $1 label, $2 expected message part, rest: arguments
    local label=$1 expected=$2 status=0 out
    shift 2
    rm -f "$work/args"
    out="$(run "$@")" || status=$?
    if ((status != 0)) && grep -q -- "$expected" <<<"$out" && [[ ! -e "$work/args" ]]; then
      echo "PASS: self-test: $label is refused before any database call" >&2
    else
      echo "FAIL: self-test: $label (exit $status): $out" >&2; problems=$((problems + 1))
    fi
  }
  expect_refusal "no arguments" "both are required"
  expect_refusal "no reason" "both are required" admin
  expect_refusal "a blank reason" "a reason is required" admin "   "
  expect_refusal "a blank username" "a username is required" " " "lost phone"
  expect_refusal "a 501-character reason" "longer than 500" admin "$(printf 'x%.0s' {1..501})"

  local username="o'brien; DROP TABLE x;--" reason="lost phone and codes, ticket 42"
  status=0
  out="$(run "$username" "$reason")" || status=$?
  if ((status == 0)) && grep -q "account 0190b000-0000-7000-8000-000000000042 is revoked" <<<"$out"; then
    echo "PASS: self-test: a reset reports the account id" >&2
  else
    echo "FAIL: self-test: a reset (exit $status): $out" >&2; problems=$((problems + 1))
  fi
  if grep -qxF -- "username=$username" "$work/args" && grep -qxF -- "reason=$reason" "$work/args" \
    && grep -qxF -- "ON_ERROR_STOP=1" "$work/args" && grep -qxF fakepg "$work/args"; then
    echo "PASS: self-test: the username and reason travel as psql variables" >&2
  else
    echo "FAIL: self-test: psql arguments: $(tr '\n' ' ' <"$work/args")" >&2; problems=$((problems + 1))
  fi
  if ! grep -qF "o'brien" "$work/stdin" && ! grep -qF "ticket 42" "$work/stdin"; then
    echo "PASS: self-test: neither value is inside the SQL text" >&2
  else
    echo "FAIL: self-test: a value leaked into the SQL text" >&2; problems=$((problems + 1))
  fi
  if [[ "$(grep -c '^BEGIN;$' "$work/stdin")" == 1 && "$(grep -c '^COMMIT;$' "$work/stdin")" == 1 ]] \
    && awk '/^BEGIN;$/ {b=NR} /mfa_break_glass_reset/ {r=NR} /end_account_sessions/ {e=NR}
            /record_console_event/ {a=NR} /^COMMIT;$/ {c=NR}
            END { exit !(b && r > b && e > r && a > e && c > a) }' "$work/stdin" \
    && grep -q 'ON_ERROR_STOP on' "$work/stdin"; then
    echo "PASS: self-test: one transaction: BEGIN, reset, end sessions, audit, COMMIT, stopping at the first error" >&2
  else
    echo "FAIL: self-test: the psql transaction: $(tr '\n' ' ' <"$work/stdin")" >&2; problems=$((problems + 1))
  fi
  if ! grep -qiE "secret|recovery|totp" <<<"$out"; then
    echo "PASS: self-test: the output names no secret" >&2
  else
    echo "FAIL: self-test: the output mentions a secret: $out" >&2; problems=$((problems + 1))
  fi
  ((problems == 0))
}

case "${1:-}" in
  --self-test) self_test ;;
  *) break_glass "$@" ;;
esac
