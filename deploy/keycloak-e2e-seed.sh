#!/usr/bin/env bash
# Development and end-to-end environments only (frontend R1 gap G16, ADR 0043): a tenant, its first store and its
# tenant administrator, who signs in with a password and a TOTP code whose secret the e2e tests know.
#   1. As Keycloak's master administrator, in realm restaurantpro: the administrator user (username = e-mail,
#      e-mail verified, no required action) with the password E2E_ADMIN_PASSWORD and exactly one OTP credential,
#      TOTP HmacSHA1 / 6 digits / 30 s (the realm's policy) on the base32 secret E2E_ADMIN_TOTP_SECRET.
#   2. Through the public platform API (D181, POST /api/v1/platform/tenants) as the ops-realm client e2e-seed (a
#      service account with platform-operator, enabled for this run only; its secret is regenerated for this run,
#      held in memory only, and when the script ends the secret is regenerated again and the client disabled): the
#      tenant, its first store and its administrator. Onboarding invites the
#      administrator by e-mail, finds the user of step 1 and grants it the tenant's ACCOUNT membership.
# Idempotent: a rerun sets the same password and replaces the OTP credential (a changed secret takes effect), and
# replays the same onboarding command (D181: idempotent by tenant code and request). Run it once per environment
# after run-local.sh is up; again to change the password or the secret.
#
# Refuses to run unless the Keycloak URL, the issuer Keycloak announces for the tenant realm and the backend URL are
# all http(s)://localhost, 127.0.0.1 or [::1], with an optional port and path and nothing else (no user info, no
# query or fragment): never a shared or production environment. No secret is in this file, none is printed, none is
# written to a file, none is passed on a command line: curl reads bodies on stdin and the bearer header from a
# process substitution, jq reads the password and the TOTP secret from its environment.
#
# Usage: deploy/keycloak-e2e-seed.sh --url URL --admin-password-file FILE --backend-url URL [--admin USER]
# Environment: E2E_ADMIN_PASSWORD, E2E_ADMIN_TOTP_SECRET (required);
#              E2E_ADMIN_EMAIL (default e2e-admin@restaurantpro.test), E2E_TENANT_CODE (default e2e).
# Prints {"tenantId", "tenantCode", "adminEmail", "adminUserId"} on stdout. Needs curl and jq. Bash 3.2 compatible.
set -euo pipefail
umask 077

TENANT_REALM=restaurantpro
OPS_REALM=restaurantpro-ops
SEED_CLIENT=e2e-seed
url=""
backend=""
admin="admin"
password_file=""

die() { echo "keycloak-e2e-seed: $*" >&2; exit 1; }
log() { echo "keycloak-e2e-seed: $*" >&2; }

while (($#)); do
  case "$1" in
    --url) url="${2%/}"; shift 2 ;;
    --backend-url) backend="${2%/}"; shift 2 ;;
    --admin) admin="${2:?}"; shift 2 ;;
    --admin-password-file) password_file="${2:?}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$url" && -n "$backend" && -f "$password_file" ]] \
  || die "usage: --url URL --admin-password-file FILE --backend-url URL"
for tool in curl jq; do command -v "$tool" >/dev/null || die "$tool is required"; done

email="${E2E_ADMIN_EMAIL:-e2e-admin@restaurantpro.test}"
code="${E2E_TENANT_CODE:-e2e}"
[[ -n "${E2E_ADMIN_PASSWORD:-}" ]] || die "E2E_ADMIN_PASSWORD is not set"
secret="$(printf '%s' "${E2E_ADMIN_TOTP_SECRET:-}" | tr '[:lower:]' '[:upper:]' | tr -d ' =')"
[[ "$secret" =~ ^[A-Z2-7]{16,}$ ]] || die "E2E_ADMIN_TOTP_SECRET must be base32 (RFC 4648, A-Z and 2-7), 16 characters or more"
[[ "$code" =~ ^[a-z0-9][a-z0-9-]{1,62}$ ]] || die "E2E_TENANT_CODE must match [a-z0-9][a-z0-9-]{1,62}"

# --- the dev-only guard, before any credential is used ------------------------------------------------------------
# The whole URL is matched, not a host extracted from it: "http://localhost:80@evil/" names the host evil.
LOOPBACK_URL='^https?://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$'
require_loopback() { # $1 what, $2 URL
  [[ "$2" =~ $LOOPBACK_URL ]] \
    || die "refused: $1 $2 is not a development address (http(s)://localhost, 127.0.0.1 or [::1], optional port and path)"
}
require_loopback "Keycloak" "$url"
require_loopback "backend" "$backend"
issuer="$(curl -fsS "$url/realms/$TENANT_REALM/.well-known/openid-configuration" | jq -r .issuer)" \
  || die "no realm $TENANT_REALM at $url"
require_loopback "issuer" "$issuer"

# --- Keycloak admin API -------------------------------------------------------------------------------------------
admin_token() {
  tr -d '\n' <"$password_file" | curl -fsS "$url/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$admin" --data-urlencode password@- \
    | jq -r .access_token
}
TOKEN="$(admin_token)" || die "the master administrator could not log in"
# $1 token: the header, for curl -H @<(bearer ...) (printf is a builtin: the token is in no process's arguments)
bearer() { printf 'Authorization: Bearer %s\n' "$1"; }
# $1 answer of curl -w '\n%{http_code}': its status
status_of() { printf '%s' "${1##*$'\n'}"; }
# $1 method, $2 path under /admin/realms; the JSON body, if any, on stdin (never a command-line argument). Prints the
# answer's body (held in memory, never in a file); fails on any status but 2xx, including 000 (no answer at all).
api() {
  local method=$1 path=$2 answer
  if [[ "$method" == GET || "$method" == DELETE ]]; then
    answer="$(curl -sS -w '\n%{http_code}' -X "$method" "$url/admin/realms$path" -H @<(bearer "$TOKEN"))" || true
  else
    answer="$(curl -sS -w '\n%{http_code}' -X "$method" "$url/admin/realms$path" -H @<(bearer "$TOKEN") \
      -H 'Content-Type: application/json' --data-binary @-)" || true
  fi
  [[ "$(status_of "$answer")" =~ ^2[0-9][0-9]$ ]] || die "$method $path answered $(status_of "$answer")"
  printf '%s' "${answer%$'\n'*}"
}
encode() { jq -rn --arg v "$1" '$v | @uri'; }
user_id() { api GET "/$TENANT_REALM/users?exact=true&email=$(encode "$email")" | jq -r '.[0].id // empty'; }

# --- 1. the administrator's Keycloak user -------------------------------------------------------------------------
# jq takes the password and the secret from its environment (env.*), never as arguments. Keycloak refuses two
# credentials of a user with one label (409): each run's label is its own.
# shellcheck disable=SC2016 # jq variables and env.*, not shell expansions
OTP_DEF='def otp: {type: "otp", userLabel: $label, secretData: ({value: env.E2E_SEED_TOTP_SECRET} | tojson),
  credentialData: ({subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1",
                    secretEncoding: "BASE32"} | tojson)};
  def password: {type: "password", value: env.E2E_ADMIN_PASSWORD, temporary: false};'
# $@ jq arguments and filter (OTP_DEF in front), with the secret in jq's environment only
secret_jq() { E2E_SEED_TOTP_SECRET="$secret" jq -cn --arg label "e2e-$(date -u +%Y%m%dT%H%M%SZ)" "$@"; }
user="$(user_id)"
if [[ -z "$user" ]]; then
  # shellcheck disable=SC2016 # jq variables and env.*, not shell expansions
  secret_jq --arg e "$email" "$OTP_DEF"'{username: $e, email: $e, emailVerified: true, enabled: true,
      firstName: "E2E", lastName: "Administrador", requiredActions: [], credentials: [password, otp]}' \
    | api POST "/$TENANT_REALM/users" >/dev/null
  user="$(user_id)"
  log "user $user created with a password and an OTP credential"
else
  old_otps="$(api GET "/$TENANT_REALM/users/$user/credentials" | jq -r '.[] | select(.type == "otp") | .id')"
  jq -cn '{enabled: true, emailVerified: true, requiredActions: []}' | api PUT "/$TENANT_REALM/users/$user" >/dev/null
  secret_jq "$OTP_DEF password" | api PUT "/$TENANT_REALM/users/$user/reset-password" >/dev/null
  # the new credential first, then the old ones removed: exactly one OTP credential, on the current secret
  secret_jq "$OTP_DEF"'{credentials: [otp]}' | api PUT "/$TENANT_REALM/users/$user" >/dev/null
  for old in $old_otps; do api DELETE "/$TENANT_REALM/users/$user/credentials/$old" >/dev/null; done
  log "user $user: password set, OTP credential replaced"
fi

# --- 2. the tenant, through the platform API ----------------------------------------------------------------------
seed_client="$(api GET "/$OPS_REALM/clients?clientId=$SEED_CLIENT" | jq -r '.[0].id // empty')"
if [[ -z "$seed_client" ]]; then
  jq -cn --arg id "$SEED_CLIENT" '{clientId: $id, enabled: false, publicClient: false,
    clientAuthenticatorType: "client-secret", serviceAccountsEnabled: true, standardFlowEnabled: false,
    directAccessGrantsEnabled: false, implicitFlowEnabled: false,
    protocolMappers: [{name: "restaurantpro-api audience", protocol: "openid-connect",
      protocolMapper: "oidc-audience-mapper", config: {"included.client.audience": "restaurantpro-api",
      "access.token.claim": "true", "id.token.claim": "false"}}]}' | api POST "/$OPS_REALM/clients" >/dev/null
  seed_client="$(api GET "/$OPS_REALM/clients?clientId=$SEED_CLIENT" | jq -r '.[0].id')"
  log "client $SEED_CLIENT created in $OPS_REALM (disabled)"
fi
# Enabled for this run only. The trap is armed before the client gets its role or is enabled: when the script ends,
# whatever happened, its secret is replaced and it is disabled, with a fresh admin token (the run's own lasts 60 s).
retire_seed_client() {
  TOKEN="$(admin_token)" || die "the master administrator could not log in"
  printf '' | api POST "/$OPS_REALM/clients/$seed_client/client-secret" >/dev/null
  jq -cn '{enabled: false}' | api PUT "/$OPS_REALM/clients/$seed_client" >/dev/null
}
trap '(retire_seed_client) || log "WARNING: could not disable $SEED_CLIENT in $OPS_REALM"' EXIT
service_account="$(api GET "/$OPS_REALM/clients/$seed_client/service-account-user" | jq -r .id)"
api GET "/$OPS_REALM/roles/platform-operator" | jq -c '[.]' \
  | api POST "/$OPS_REALM/users/$service_account/role-mappings/realm" >/dev/null
jq -cn '{enabled: true}' | api PUT "/$OPS_REALM/clients/$seed_client" >/dev/null
operator="$(printf '' | api POST "/$OPS_REALM/clients/$seed_client/client-secret" | jq -j .value \
  | curl -fsS "$url/realms/$OPS_REALM/protocol/openid-connect/token" -d grant_type=client_credentials \
      -d "client_id=$SEED_CLIENT" --data-urlencode client_secret@- | jq -r .access_token)" \
  || die "$SEED_CLIENT could not get a token"

onboarding="$(jq -cn --arg c "$code" --arg e "$email" '{code: $c, legalName: "Restaurante E2E Lda",
  taxId: "400000001", country: "MZ", currency: "MZN", firstStoreName: "Loja E2E", adminEmail: $e}')"
# D181: the same command replayed until the event chain has made the tenant ACTIVE
for attempt in $(seq 1 30); do
  answer="$(printf '%s' "$onboarding" | curl -sS -w '\n%{http_code}' -X POST "$backend/api/v1/platform/tenants" \
    -H @<(bearer "$operator") -H 'Content-Type: application/json' --data-binary @-)"
  status="$(status_of "$answer")"
  answer="${answer%$'\n'*}"
  [[ "$status" == 202 ]] || die "onboarding answered $status: $(jq -r '.code // .title // empty' <<<"$answer" 2>/dev/null)"
  tenant="$(jq -r .id <<<"$answer")"
  [[ "$(jq -r .status <<<"$answer")" == ACTIVE ]] && break
  ((attempt < 30)) || die "tenant $tenant is still $(jq -r .status <<<"$answer") after 30 attempts"
  sleep 2
done
log "tenant $code ($tenant) is ACTIVE; $email is its administrator"
jq -n --arg t "$tenant" --arg c "$code" --arg e "$email" --arg u "$user" \
  '{tenantId: $t, tenantCode: $c, adminEmail: $e, adminUserId: $u}'
