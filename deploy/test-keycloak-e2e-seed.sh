#!/usr/bin/env bash
# Verification of the dev realm's BackOffice dev origin (frontend R1 gap G15) and of deploy/keycloak-e2e-seed.sh (G16),
# against the running development stack (backend/run-local.sh: Keycloak with the deploy/keycloak realms, the
# provisioners, the backend). Per run it picks a new tenant code, e-mail, password and TOTP secret (never printed),
# then, failing on the first miss:
#   guard        the seed refuses a non-loopback Keycloak and a non-loopback backend, before any request
#   seed         the seed creates the tenant (ACTIVE) and its administrator; a rerun with a new password and a new
#                secret answers the same tenant and user and leaves exactly one OTP credential
#   dev origin   a sign-in of backoffice-bff with redirect_uri <dev origin>/login/oauth2/code/keycloak, with the
#                password and a TOTP code (acr_values=2), is redirected back to the dev origin with a code
#   acr 2        the same sign-in through a throw-away public client of the tenant realm (created and removed by this
#                run: backoffice-bff's code needs the BFF's key) gives an access token with acr "2" whose subject is
#                the seeded user
# Leaves the run's tenant and user behind (dev data); removes the throw-away client.
#
# Usage: deploy/test-keycloak-e2e-seed.sh --url URL --admin-password-file FILE --backend-url URL [--dev-origin URL]
#        (dev origin default http://localhost:5174). Needs curl, jq, openssl.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REALM=restaurantpro
url=""
backend=""
password_file=""
origin="http://localhost:5174"

die() { echo "FAIL: $*" >&2; exit 1; }
pass() { echo "PASS: $*"; }

while (($#)); do
  case "$1" in
    --url) url="${2%/}"; shift 2 ;;
    --backend-url) backend="${2%/}"; shift 2 ;;
    --admin-password-file) password_file="${2:?}"; shift 2 ;;
    --dev-origin) origin="${2%/}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$url" && -n "$backend" && -f "$password_file" ]] \
  || die "usage: --url URL --admin-password-file FILE --backend-url URL [--dev-origin URL]"
work="$(mktemp -d)"
verify_client=""
cleanup() {
  if [[ -n "$verify_client" ]]; then
    curl -fsS -o /dev/null -X DELETE "$url/admin/realms/$REALM/clients/$verify_client" \
      -H "Authorization: Bearer $(admin_token)" || echo "could not remove the throw-away client" >&2
  fi
  rm -rf "$work"
}
trap cleanup EXIT

# $1 alphabet, $2 length (tr ends on SIGPIPE when head has enough, which pipefail would report)
random_of() { { LC_ALL=C tr -dc "$1" </dev/urandom || true; } | head -c "$2"; }
random_base32() { random_of 'A-Z2-7' "$1"; }
run="$(random_of 'a-z0-9' 8)"
E2E_TENANT_CODE="e2e-verify-$run"
E2E_ADMIN_EMAIL="e2e-verify-$run@restaurantpro.test"
new_credentials() { E2E_ADMIN_PASSWORD="Pw-$(random_base32 20)"; E2E_ADMIN_TOTP_SECRET="$(random_base32 32)"; }
new_credentials
export E2E_TENANT_CODE E2E_ADMIN_EMAIL E2E_ADMIN_PASSWORD E2E_ADMIN_TOTP_SECRET
seed() { "$DEPLOY_DIR/keycloak-e2e-seed.sh" --admin-password-file "$password_file" "$@"; }

# RFC 6238 (HmacSHA1, 6 digits, 30 s), the realm's OTP policy; $1 base32 secret, $2 unix time
base32_hex() {
  local alphabet=ABCDEFGHIJKLMNOPQRSTUVWXYZ234567 s prefix bits=0 value=0 i
  s="$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -d '= ')"
  for ((i = 0; i < ${#s}; i++)); do
    prefix="${alphabet%%"${s:i:1}"*}"
    value=$(((value << 5) | ${#prefix}))
    bits=$((bits + 5))
    if ((bits >= 8)); then
      bits=$((bits - 8))
      printf '%02x' $(((value >> bits) & 255))
      value=$((value & ((1 << bits) - 1)))
    fi
  done
}
totp() {
  local counter mac offset
  counter="$(printf '%016x' $(($2 / 30)))"
  # shellcheck disable=SC2059,SC2001 # the format is the counter's bytes as \xHH escapes
  mac="$(printf "$(sed 's/../\\x&/g' <<<"$counter")" \
    | openssl dgst -sha1 -mac HMAC -macopt "hexkey:$(base32_hex "$1")" | sed 's/^.*= *//')"
  offset=$((16#${mac:39:1} * 2))
  printf '%06d' $(((16#${mac:offset:8} & 0x7fffffff) % 1000000))
}
# Keycloak refuses a code used once (otpPolicyCodeReusable false): each sign-in waits for a period of its own.
# Sets OTP (not a command substitution, which would lose last_period).
last_period=-1
fresh_totp() {
  local now
  now="$(date +%s)"
  while ((now / 30 == last_period)); do sleep 1; now="$(date +%s)"; done
  last_period=$((now / 30))
  OTP="$(totp "$1" "$now")"
}

admin_token() {
  tr -d '\n' <"$password_file" | curl -fsS "$url/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli -d username=admin --data-urlencode password@- | jq -r .access_token
}
form_action() { # $1 form id; the page on stdin; empty when there is no such form
  { tr '\n' ' ' | grep -o "<form [^>]*id=\"$1\"[^>]*>" || true; } | sed 's/.* action="\([^"]*\)".*/\1/; s/&amp;/\&/g'
}
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# $1 client id, $2 redirect URI: password + TOTP through the browser flow (acr_values=2, PKCE S256 as backoffice-bff
# requires); sets REDIRECT and VERIFIER
sign_in() {
  local jar="$work/jar-$1" page action
  rm -f "$jar"
  VERIFIER="$(random_of 'A-Za-z0-9' 64)"
  page="$(curl -sS -c "$jar" -b "$jar" -G "$url/realms/$REALM/protocol/openid-connect/auth" \
    --data-urlencode "client_id=$1" --data-urlencode "redirect_uri=$2" -d response_type=code -d scope=openid \
    -d acr_values=2 -d state=verify -d code_challenge_method=S256 \
    --data-urlencode "code_challenge=$(printf '%s' "$VERIFIER" | openssl dgst -sha256 -binary | b64url)")"
  action="$(form_action kc-form-login <<<"$page")"
  [[ -n "$action" ]] || die "$1: no login form for redirect_uri $2: $(grep -o 'Invalid parameter[^<]*' <<<"$page" || true)"
  page="$(printf '%s' "$E2E_ADMIN_PASSWORD" | curl -fsS -c "$jar" -b "$jar" "$action" \
    --data-urlencode "username=$E2E_ADMIN_EMAIL" --data-urlencode password@-)" || die "$1: the password form failed"
  action="$(form_action kc-otp-login-form <<<"$page")"
  [[ -n "$action" ]] || die "$1: no OTP form after the password (wrong password, or no OTP credential)"
  fresh_totp "$E2E_ADMIN_TOTP_SECRET"
  REDIRECT="$(curl -sS -o /dev/null -c "$jar" -b "$jar" -w '%{redirect_url}' "$action" --data-urlencode "otp=$OTP")"
}
claims() { cut -d. -f2 | tr '_-' '/+' | { read -r p; while ((${#p} % 4)); do p="$p="; done; printf '%s' "$p"; } \
  | openssl base64 -d -A; }

# --- guard -------------------------------------------------------------------------------------------------------
if out="$(seed --url http://keycloak.example.com:8080 --backend-url "$backend" 2>&1)"; then
  die "the seed ran against a non-loopback Keycloak"
fi
[[ "$out" == *refused* ]] || die "unexpected refusal: $out"
if out="$(seed --url "$url" --backend-url https://api.example.com 2>&1)"; then
  die "the seed ran against a non-loopback backend"
fi
[[ "$out" == *refused* ]] || die "unexpected refusal: $out"
pass "guard: refuses a non-loopback Keycloak and backend"

# --- seed, twice --------------------------------------------------------------------------------------------------
first="$(seed --url "$url" --backend-url "$backend")" || die "the seed failed"
new_credentials
second="$(seed --url "$url" --backend-url "$backend")" || die "the rerun of the seed failed"
[[ "$(jq -r .tenantId <<<"$first")" == "$(jq -r .tenantId <<<"$second")" ]] || die "the rerun made another tenant"
user="$(jq -r .adminUserId <<<"$second")"
[[ "$(jq -r .adminUserId <<<"$first")" == "$user" ]] || die "the rerun made another user"
otps="$(curl -fsS "$url/admin/realms/$REALM/users/$user/credentials" -H "Authorization: Bearer $(admin_token)" \
  | jq '[.[] | select(.type == "otp")] | length')"
[[ "$otps" == 1 ]] || die "the user has $otps OTP credentials"
pass "seed: tenant $(jq -r .tenantId <<<"$second") ACTIVE; a rerun keeps the tenant and the user, one OTP credential"

# --- G15: backoffice-bff returns to the dev origin -----------------------------------------------------------------
sign_in backoffice-bff "$origin/login/oauth2/code/keycloak"
redirect="$REDIRECT"
[[ "$redirect" == "$origin/login/oauth2/code/keycloak?"*"code="* ]] \
  || die "backoffice-bff did not return to $origin (got: ${redirect%%\?*})"
pass "dev origin: backoffice-bff sign-in (password + TOTP) returns to $origin/login/oauth2/code/keycloak"

# --- G16: acr 2 ---------------------------------------------------------------------------------------------------
verify_id="e2e-verify-$run"
jq -cn --arg id "$verify_id" --arg ru "$origin/verify" '{clientId: $id, publicClient: true, standardFlowEnabled: true,
  directAccessGrantsEnabled: false, implicitFlowEnabled: false, redirectUris: [$ru]}' \
  | curl -fsS -o /dev/null "$url/admin/realms/$REALM/clients" -H "Authorization: Bearer $(admin_token)" \
      -H 'Content-Type: application/json' --data-binary @-
verify_client="$(curl -fsS "$url/admin/realms/$REALM/clients?clientId=$verify_id" \
  -H "Authorization: Bearer $(admin_token)" | jq -r '.[0].id')"
sign_in "$verify_id" "$origin/verify"
redirect="$REDIRECT"
code="$(sed -n 's/.*[?&]code=\([^&]*\).*/\1/p' <<<"$redirect")"
[[ -n "$code" ]] || die "no authorization code (got: ${redirect%%\?*})"
access="$(curl -fsS "$url/realms/$REALM/protocol/openid-connect/token" -d grant_type=authorization_code \
  -d "client_id=$verify_id" --data-urlencode "redirect_uri=$origin/verify" --data-urlencode "code=$code" \
  --data-urlencode "code_verifier=$VERIFIER" \
  | jq -r .access_token)"
acr="$(claims <<<"$access" | jq -r .acr)"
sub="$(claims <<<"$access" | jq -r .sub)"
[[ "$acr" == 2 ]] || die "acr is $acr, not 2"
[[ "$sub" == "$user" ]] || die "the token's subject is not the seeded user"
pass "acr 2: the seeded user signs in with the password and a TOTP code from E2E_ADMIN_TOTP_SECRET"
