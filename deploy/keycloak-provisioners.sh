#!/usr/bin/env bash
# The backend's two Keycloak service accounts (D181, D182, O1 option 3), set up as Keycloak's master administrator.
# Idempotent: run once per environment, and again to rotate a key (--rotate).
#   - device-provisioner, realm restaurantpro-devices (deploy/keycloak/realm-restaurantpro-devices.json: the realm is
#     imported if missing): manage-clients + view-clients in that realm only;
#   - tenancy-provisioner, realm restaurantpro: created if missing, FGAP v2 on (adminPermissionsEnabled) with Users and
#     Organizations permissions (view, manage) and no Clients permission. (Until S72 adds it to
#     realm-restaurantpro.json; then only its permissions and key are set here.)
# Each authenticates with private_key_jwt (ES256): its P-256 private key is written to KEYS_DIR as
# <client>.pem (PKCS#8, 0600; give the backend its path in RESTAURANTPRO_KEYCLOAK_ADMIN_KEY_FILE and
# RESTAURANTPRO_KEYCLOAK_DEVICES_ADMIN_KEY_FILE) and only the public JWK is registered on the client. No secret exists,
# none is printed. Optional --smtp-host HOST[:PORT] points the tenant realm's email at a relay (development: mailpit).
#
# Usage: deploy/keycloak-provisioners.sh --url URL --admin-password-file FILE --keys-dir DIR [--admin USER]
#                                        [--rotate] [--smtp-host HOST[:PORT]]
# Needs curl, jq and openssl.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TENANT_REALM=restaurantpro
DEVICE_REALM=restaurantpro-devices
url=""
admin="admin"
password_file=""
keys=""
rotate=false
smtp=""

die() { echo "keycloak-provisioners: $*" >&2; exit 1; }
log() { echo "keycloak-provisioners: $*" >&2; }

while (($#)); do
  case "$1" in
    --url) url="${2%/}"; shift 2 ;;
    --admin) admin="${2:?}"; shift 2 ;;
    --admin-password-file) password_file="${2:?}"; shift 2 ;;
    --keys-dir) keys="${2:?}"; shift 2 ;;
    --rotate) rotate=true; shift ;;
    --smtp-host) smtp="${2:?}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$url" && -f "$password_file" && -n "$keys" ]] || die "usage: --url URL --admin-password-file FILE --keys-dir DIR"
for tool in curl jq openssl; do command -v "$tool" >/dev/null || die "$tool is required"; done
mkdir -p "$keys"
chmod 700 "$keys"

token() {
  tr -d '\n' <"$password_file" | curl -fsS "$url/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$admin" --data-urlencode password@- \
    | jq -r .access_token
}
TOKEN="$(token)" || die "the master administrator could not log in"
# $1 method, $2 path under /admin/realms, [$3 JSON body]; prints the body; fails on a status >= 400 unless $4 = ok-409
api() {
  local method=$1 path=$2 body=${3:-} allow=${4:-} out status
  out="$(mktemp)"
  status="$(curl -sS -o "$out" -w '%{http_code}' -X "$method" "$url/admin/realms$path" -H "Authorization: Bearer $TOKEN" \
    ${body:+-H 'Content-Type: application/json' --data-binary "$body"})"
  if ((status >= 400)) && ! [[ "$allow" == ok-409 && "$status" == 409 ]]; then
    rm -f "$out"
    die "$method $path answered $status"
  fi
  cat "$out"
  rm -f "$out"
}
client_uuid() { api GET "/$1/clients?clientId=$2" | jq -r '.[0].id // empty'; }
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# $1 client: its key (kept unless --rotate) and its public JWK.
key_of() {
  local pem="$keys/$1.pem" xy
  if [[ ! -f "$pem" || "$rotate" == true ]]; then
    openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$pem.tmp"
    chmod 600 "$pem.tmp"
    mv "$pem.tmp" "$pem"
    log "new key for $1 in $pem"
  fi
  xy="$(mktemp)"
  openssl pkey -in "$pem" -pubout -outform DER | tail -c 64 >"$xy"
  jq -cn --arg kid "$1-$(date -u +%Y%m%d)" --arg x "$(head -c 32 "$xy" | b64url)" --arg y "$(tail -c 32 "$xy" | b64url)" \
    '{kty: "EC", crv: "P-256", use: "sig", alg: "ES256", kid: $kid, x: $x, y: $y}'
  rm -f "$xy"
}

# $1 realm, $2 client: client-jwt (ES256) with the public JWK of its key.
register_key() {
  local jwk uuid
  jwk="$(key_of "$2")"
  uuid="$(client_uuid "$1" "$2")"
  [[ -n "$uuid" ]] || die "no client $2 in realm $1"
  api PUT "/$1/clients/$uuid" "$(jq -cn --arg jwks "{\"keys\":[$jwk]}" '{clientAuthenticatorType: "client-jwt",
    attributes: {"use.jwks.string": "true", "use.jwks.url": "false", "jwks.string": $jwks,
                 "token.endpoint.auth.signing.alg": "ES256"}}')" >/dev/null
  log "$2 ($1): public key registered"
}

# --- device-provisioner -------------------------------------------------------------------------------------------
if ! curl -fsS -o /dev/null -H "Authorization: Bearer $TOKEN" "$url/admin/realms/$DEVICE_REALM"; then
  api POST "" "$(cat "$DEPLOY_DIR/keycloak/realm-restaurantpro-devices.json")" >/dev/null
  log "realm $DEVICE_REALM imported"
fi
register_key "$DEVICE_REALM" device-provisioner

# --- tenancy-provisioner ------------------------------------------------------------------------------------------
realm="$(api GET "/$TENANT_REALM")"
realm="$(jq -c '.adminPermissionsEnabled = true' <<<"$realm")"
if [[ -n "$smtp" ]]; then
  realm="$(jq -c --arg host "${smtp%%:*}" --arg port "$([[ "$smtp" == *:* ]] && echo "${smtp##*:}" || echo 25)" \
    '.smtpServer = {host: $host, port: $port, from: "noreply@restaurantpro.local", auth: "false", ssl: "false", starttls: "false"}' <<<"$realm")"
fi
api PUT "/$TENANT_REALM" "$realm" >/dev/null
if [[ -z "$(client_uuid "$TENANT_REALM" tenancy-provisioner)" ]]; then
  api POST "/$TENANT_REALM/clients" '{"clientId":"tenancy-provisioner","enabled":true,"publicClient":false,
    "clientAuthenticatorType":"client-jwt","serviceAccountsEnabled":true,"standardFlowEnabled":false,
    "directAccessGrantsEnabled":false,"implicitFlowEnabled":false}' >/dev/null
  log "tenancy-provisioner created in $TENANT_REALM"
fi
register_key "$TENANT_REALM" tenancy-provisioner
provisioner="$(client_uuid "$TENANT_REALM" tenancy-provisioner)"
service_account="$(api GET "/$TENANT_REALM/clients/$provisioner/service-account-user" | jq -r .id)"
authz="/$TENANT_REALM/clients/$(client_uuid "$TENANT_REALM" admin-permissions)/authz/resource-server"
api POST "$authz/policy/user" "$(jq -cn --arg u "$service_account" \
  '{name: "caller-is-tenancy-provisioner", users: [$u], logic: "POSITIVE"}')" ok-409 >/dev/null
policy="$(api GET "$authz/policy?name=caller-is-tenancy-provisioner" | jq -r '.[0].id')"
for type in Users Organizations; do
  api POST "$authz/permission/scope" "$(jq -cn --arg t "$type" --arg p "$policy" \
    '{name: ("tenancy-provisioner-" + ($t | ascii_downcase)), resourceType: $t, scopes: ["view", "manage"], policies: [$p]}')" \
    ok-409 >/dev/null
done
log "tenancy-provisioner ($TENANT_REALM): FGAP v2 Users + Organizations (view, manage), no Clients permission"
