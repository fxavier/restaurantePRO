#!/usr/bin/env bash
# Development and end-to-end environments only (frontend R1 gap G16, ADR 0043): a tenant, its first store and its
# tenant administrator, who signs in with a password and a TOTP code whose secret the e2e tests know.
#   1. As Keycloak's master administrator, in realm restaurantpro: the administrator user (username = e-mail,
#      e-mail verified, no required action) with the password E2E_ADMIN_PASSWORD and exactly one OTP credential,
#      TOTP HmacSHA1 / 6 digits / 30 s (the realm's policy) on the base32 secret E2E_ADMIN_TOTP_SECRET.
#   2. Through the public platform API (D181, POST /api/v1/platform/tenants) as the ops-realm client e2e-seed (a
#      service account with platform-operator; its secret is regenerated for this run, held in memory only and
#      regenerated again at the end): the tenant, its first store and its administrator. Onboarding invites the
#      administrator by e-mail, finds the user of step 1 and grants it the tenant's ACCOUNT membership.
# Idempotent: a rerun sets the same password and replaces the OTP credential (a changed secret takes effect), and
# replays the same onboarding command (D181: idempotent by tenant code and request). Run it once per environment
# after run-local.sh is up; again to change the password or the secret.
#
# Refuses to run unless the Keycloak URL, the issuer Keycloak announces for the tenant realm and the backend URL are
# all on the loopback interface (localhost, 127.0.0.1, [::1]): never a shared or production environment. No secret is
# in this file, none is printed, none is passed on a command line (curl reads them on stdin).
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
host_of() {
  local rest="${1#*://}"
  rest="${rest%%/*}"
  if [[ "$rest" == \[* ]]; then echo "${rest%%]*}]"; else echo "${rest%%:*}"; fi
}
require_loopback() { # $1 what, $2 URL
  case "$(host_of "$2")" in
    localhost | 127.0.0.1 | '[::1]') ;;
    *) die "refused: $1 $2 is not a development address (allowed hosts: localhost, 127.0.0.1, [::1])" ;;
  esac
}
require_loopback "Keycloak" "$url"
require_loopback "backend" "$backend"
issuer="$(curl -fsS "$url/realms/$TENANT_REALM/.well-known/openid-configuration" | jq -r .issuer)" \
  || die "no realm $TENANT_REALM at $url"
require_loopback "issuer" "$issuer"

# --- Keycloak admin API -------------------------------------------------------------------------------------------
TOKEN="$(tr -d '\n' <"$password_file" | curl -fsS "$url/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$admin" --data-urlencode password@- \
  | jq -r .access_token)" || die "the master administrator could not log in"
# $1 method, $2 path under /admin/realms; the JSON body, if any, on stdin (never a command-line argument). Prints the
# answer's body; fails on a status >= 400.
api() {
  local method=$1 path=$2 out status
  out="$(mktemp)"
  if [[ "$method" == GET || "$method" == DELETE ]]; then
    status="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" "$url/admin/realms$path" \
      -H "Authorization: Bearer $TOKEN")"
  else
    status="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" "$url/admin/realms$path" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' --data-binary @-)"
  fi
  if ((status >= 400)); then
    rm -f "$out"
    die "$method $path answered $status"
  fi
  cat "$out"
  rm -f "$out"
}
encode() { jq -rn --arg v "$1" '$v | @uri'; }
user_id() { api GET "/$TENANT_REALM/users?exact=true&email=$(encode "$email")" | jq -r '.[0].id // empty'; }

# --- 1. the administrator's Keycloak user -------------------------------------------------------------------------
# Keycloak refuses two credentials of a user with one label (409): each run's label is its own.
otp_credential() {
  jq -cn --arg s "$secret" --arg label "e2e-$(date -u +%Y%m%dT%H%M%SZ)" '{type: "otp", userLabel: $label, secretData: ({value: $s} | tojson),
    credentialData: ({subType: "totp", digits: 6, counter: 0, period: 30, algorithm: "HmacSHA1",
                      secretEncoding: "BASE32"} | tojson)}'
}
user="$(user_id)"
if [[ -z "$user" ]]; then
  jq -cn --arg e "$email" --arg p "$E2E_ADMIN_PASSWORD" --argjson otp "$(otp_credential)" \
    '{username: $e, email: $e, emailVerified: true, enabled: true, firstName: "E2E", lastName: "Administrador",
      requiredActions: [], credentials: [{type: "password", value: $p, temporary: false}, $otp]}' \
    | api POST "/$TENANT_REALM/users" >/dev/null
  user="$(user_id)"
  log "user $user created with a password and an OTP credential"
else
  old_otps="$(api GET "/$TENANT_REALM/users/$user/credentials" | jq -r '.[] | select(.type == "otp") | .id')"
  jq -cn '{enabled: true, emailVerified: true, requiredActions: []}' | api PUT "/$TENANT_REALM/users/$user" >/dev/null
  jq -cn --arg p "$E2E_ADMIN_PASSWORD" '{type: "password", value: $p, temporary: false}' \
    | api PUT "/$TENANT_REALM/users/$user/reset-password" >/dev/null
  # the new credential first, then the old ones removed: exactly one OTP credential, on the current secret
  jq -cn --argjson otp "$(otp_credential)" '{credentials: [$otp]}' | api PUT "/$TENANT_REALM/users/$user" >/dev/null
  for old in $old_otps; do api DELETE "/$TENANT_REALM/users/$user/credentials/$old" >/dev/null; done
  log "user $user: password set, OTP credential replaced"
fi

# --- 2. the tenant, through the platform API ----------------------------------------------------------------------
seed_client="$(api GET "/$OPS_REALM/clients?clientId=$SEED_CLIENT" | jq -r '.[0].id // empty')"
if [[ -z "$seed_client" ]]; then
  jq -cn --arg id "$SEED_CLIENT" '{clientId: $id, enabled: true, publicClient: false,
    clientAuthenticatorType: "client-secret", serviceAccountsEnabled: true, standardFlowEnabled: false,
    directAccessGrantsEnabled: false, implicitFlowEnabled: false,
    protocolMappers: [{name: "restaurantpro-api audience", protocol: "openid-connect",
      protocolMapper: "oidc-audience-mapper", config: {"included.client.audience": "restaurantpro-api",
      "access.token.claim": "true", "id.token.claim": "false"}}]}' | api POST "/$OPS_REALM/clients" >/dev/null
  seed_client="$(api GET "/$OPS_REALM/clients?clientId=$SEED_CLIENT" | jq -r '.[0].id')"
  log "client $SEED_CLIENT created in $OPS_REALM"
fi
service_account="$(api GET "/$OPS_REALM/clients/$seed_client/service-account-user" | jq -r .id)"
api GET "/$OPS_REALM/roles/platform-operator" | jq -c '[.]' \
  | api POST "/$OPS_REALM/users/$service_account/role-mappings/realm" >/dev/null
retire_secret() { printf '' | api POST "/$OPS_REALM/clients/$seed_client/client-secret" >/dev/null; }
trap 'retire_secret || true' EXIT
operator="$(printf '' | api POST "/$OPS_REALM/clients/$seed_client/client-secret" | jq -j .value \
  | curl -fsS "$url/realms/$OPS_REALM/protocol/openid-connect/token" -d grant_type=client_credentials \
      -d "client_id=$SEED_CLIENT" --data-urlencode client_secret@- | jq -r .access_token)" \
  || die "$SEED_CLIENT could not get a token"

onboarding="$(jq -cn --arg c "$code" --arg e "$email" '{code: $c, legalName: "Restaurante E2E Lda",
  taxId: "400000001", country: "MZ", currency: "MZN", firstStoreName: "Loja E2E", adminEmail: $e}')"
# D181: the same command replayed until the event chain has made the tenant ACTIVE
for attempt in $(seq 1 30); do
  out="$(mktemp)"
  status="$(printf '%s' "$onboarding" | curl -sS -o "$out" -w '%{http_code}' -X POST "$backend/api/v1/platform/tenants" \
    -H "Authorization: Bearer $operator" -H 'Content-Type: application/json' --data-binary @-)"
  answer="$(cat "$out")"
  rm -f "$out"
  [[ "$status" == 202 ]] || die "onboarding answered $status: $(jq -r '.code // .title // empty' <<<"$answer" 2>/dev/null)"
  tenant="$(jq -r .id <<<"$answer")"
  [[ "$(jq -r .status <<<"$answer")" == ACTIVE ]] && break
  ((attempt < 30)) || die "tenant $tenant is still $(jq -r .status <<<"$answer") after 30 attempts"
  sleep 2
done
log "tenant $code ($tenant) is ACTIVE; $email is its administrator"
jq -n --arg t "$tenant" --arg c "$code" --arg e "$email" --arg u "$user" \
  '{tenantId: $t, tenantCode: $c, adminEmail: $e, adminUserId: $u}'
