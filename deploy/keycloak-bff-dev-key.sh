#!/usr/bin/env bash
# Development and end-to-end environments only (frontend R1, ticket 05): lets a locally run BFF sign in as
# backoffice-bff (realm restaurantpro), which authenticates with private_key_jwt and whose key nothing else registers.
#   - generates a P-256 key as a private JWK (ES256, with a kid) in --key-file, the BFF's
#     RESTAURANTPRO_BFF_CLIENT_KEY_FILE; an existing file is kept (its public half is registered again) unless --rotate;
#   - registers only the public JWK on backoffice-bff (client-jwt, ES256), as keycloak-provisioners.sh does for the
#     backend's two service accounts;
#   - with --origin, adds that BackOffice origin's redirect URI (<origin>/login/oauth2/code/keycloak), web origin and
#     post-logout redirect URI (<origin>/) to the client, keeping the ones it has.
# Idempotent. The private key never leaves --key-file (mode 0600 unless --key-mode), is never printed and is never put
# on a command line. It is a throwaway development key: never commit it and never use it beyond a loopback stack.
#
# Refuses to run unless the Keycloak URL, the issuer Keycloak announces for restaurantpro and --origin are all
# http(s)://localhost, 127.0.0.1 or [::1] with an optional port and path (the guard of keycloak-e2e-seed.sh).
#
# Usage: deploy/keycloak-bff-dev-key.sh --url URL --admin-password-file FILE --key-file FILE [--origin URL]
#                                       [--admin USER] [--rotate] [--key-mode MODE]
# --key-mode 0644 is for a key file bind-mounted into a container that runs as another user (keep its directory 0700).
# Needs curl, jq and openssl. Bash 3.2 compatible.
set -euo pipefail
umask 077

REALM=restaurantpro
CLIENT=backoffice-bff
url=""
admin="admin"
password_file=""
key_file=""
origin=""
rotate=false
key_mode=600

die() { echo "keycloak-bff-dev-key: $*" >&2; exit 1; }
log() { echo "keycloak-bff-dev-key: $*" >&2; }

while (($#)); do
  case "$1" in
    --url) url="${2%/}"; shift 2 ;;
    --admin) admin="${2:?}"; shift 2 ;;
    --admin-password-file) password_file="${2:?}"; shift 2 ;;
    --key-file) key_file="${2:?}"; shift 2 ;;
    --origin) origin="${2%/}"; shift 2 ;;
    --rotate) rotate=true; shift ;;
    --key-mode) key_mode="${2:?}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -n "$url" && -f "$password_file" && -n "$key_file" ]] \
  || die "usage: --url URL --admin-password-file FILE --key-file FILE [--origin URL]"
for tool in curl jq openssl; do command -v "$tool" >/dev/null || die "$tool is required"; done

# --- the dev-only guard, before any credential is used (as keycloak-e2e-seed.sh) ----------------------------------
LOOPBACK_URL='^https?://(localhost|127\.0\.0\.1|\[::1\])(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$'
require_loopback() { # $1 what, $2 URL
  [[ "$2" =~ $LOOPBACK_URL ]] \
    || die "refused: $1 $2 is not a development address (http(s)://localhost, 127.0.0.1 or [::1], optional port and path)"
}
require_loopback "Keycloak" "$url"
[[ -z "$origin" ]] || require_loopback "origin" "$origin"
issuer="$(curl -fsS "$url/realms/$REALM/.well-known/openid-configuration" | jq -r .issuer)" \
  || die "no realm $REALM at $url"
require_loopback "issuer" "$issuer"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

# --- the key: a private JWK; only its public half goes to Keycloak --------------------------------------------------
if [[ ! -s "$key_file" || "$rotate" == true ]]; then
  der="$(mktemp)"
  trap 'rm -f "$der"' EXIT
  # SEC1 DER of a named-curve P-256 key: 30 77 02 01 01 04 20 <d: 32 bytes> a0 0a <OID> a1 44 03 42 00 04 <x> <y>
  openssl ecparam -name prime256v1 -genkey -noout -outform DER >"$der"
  [[ "$(wc -c <"$der" | tr -d ' ')" == 121 ]] || die "unexpected key encoding from openssl"
  # jq reads the coordinates from its environment, never from its arguments
  JWK_D="$(tail -c +8 "$der" | head -c 32 | b64url)" JWK_X="$(tail -c 64 "$der" | head -c 32 | b64url)" \
    JWK_Y="$(tail -c 32 "$der" | b64url)" \
    jq -n --arg kid "$CLIENT-dev-$(date -u +%Y%m%dT%H%M%SZ)" \
      '{kty: "EC", crv: "P-256", use: "sig", alg: "ES256", kid: $kid, x: env.JWK_X, y: env.JWK_Y, d: env.JWK_D}' \
      >"$key_file.tmp"
  rm -f "$der"
  mv "$key_file.tmp" "$key_file"
  log "new key in $key_file"
fi
chmod "$key_mode" "$key_file"
public_jwk="$(jq -c '{kty, crv, use, alg, kid, x, y}' "$key_file")"

# --- the client ---------------------------------------------------------------------------------------------------
token="$(tr -d '\n' <"$password_file" | curl -fsS "$url/realms/master/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=admin-cli --data-urlencode "username=$admin" --data-urlencode password@- \
  | jq -r .access_token)" || die "the master administrator could not log in"
bearer() { printf 'Authorization: Bearer %s\n' "$token"; }
client="$(curl -fsS "$url/admin/realms/$REALM/clients?clientId=$CLIENT" -H @<(bearer) | jq -c '.[0] // empty')"
[[ -n "$client" ]] || die "no client $CLIENT in realm $REALM"
uuid="$(jq -r .id <<<"$client")"
jq -c --arg jwks "{\"keys\":[$public_jwk]}" --arg origin "$origin" '
  .clientAuthenticatorType = "client-jwt"
  | .attributes += {"use.jwks.string": "true", "use.jwks.url": "false", "jwks.string": $jwks,
                    "token.endpoint.auth.signing.alg": "ES256"}
  | if $origin == "" then . else
      .redirectUris = ((.redirectUris // []) + [$origin + "/login/oauth2/code/keycloak"] | unique)
      | .webOrigins = ((.webOrigins // []) + [$origin] | unique)
      | .attributes["post.logout.redirect.uris"] =
          ((.attributes["post.logout.redirect.uris"] // "") | split("##") | map(select(. != ""))
           | . + [$origin + "/"] | unique | join("##"))
    end' <<<"$client" \
  | curl -fsS -o /dev/null -X PUT "$url/admin/realms/$REALM/clients/$uuid" -H @<(bearer) \
      -H 'Content-Type: application/json' --data-binary @- \
  || die "could not update $CLIENT"
log "$CLIENT ($REALM): public key $(jq -r .kid <<<"$public_jwk") registered${origin:+, origin $origin allowed}"
