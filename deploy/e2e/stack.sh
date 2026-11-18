#!/usr/bin/env bash
# The end-to-end stack of the frontend (ticket 05, ADR 0041, ADR 0043): compose.yaml next to this file, project
# restaurantpro-e2e. Throwaway and loopback-only; never point it at shared or production data.
#
#   deploy/e2e/stack.sh up      secrets and keys in deploy/e2e/.run/ (git-ignored, 0700), builds both SPAs (skip with
#                               E2E_SKIP_BUILD=1), starts PostgreSQL, Keycloak and Mailpit, registers the backend's
#                               provisioner keys (keycloak-provisioners.sh) and a dev-only key and the e2e origin for
#                               backoffice-bff (keycloak-bff-dev-key.sh), then the backend, the BFF and Caddy
#   deploy/e2e/stack.sh env     the E2E_* variables Playwright needs (no secret: the admin password stays in its file)
#   deploy/e2e/stack.sh test [playwright args]   runs Playwright from frontend/ against the running stack
#   deploy/e2e/stack.sh logs    the services' logs
#   deploy/e2e/stack.sh down    removes the containers, the throwaway data and .run/ (only this project's)
#
# Every secret is generated per stack (openssl rand), lives in .run/ only, and is never printed. Needs docker (with
# Compose v2), curl, jq, openssl, and pnpm for the build.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(cd "$HERE/.." && pwd)"
FRONTEND="$(cd "$HERE/../../frontend" && pwd)"
RUN="$HERE/.run"
KEYCLOAK_URL=http://localhost:18180
BACKEND_URL=http://localhost:18080
POS_URL=http://localhost:14173
BACKOFFICE_URL=http://localhost:14174

die() { echo "e2e-stack: $*" >&2; exit 1; }
log() { echo "e2e-stack: $*" >&2; }
compose() { docker compose -f "$HERE/compose.yaml" "$@"; }

# $1 file: a random secret, kept if it exists (a running stack keeps its passwords)
secret() {
  [[ -s "$1" ]] || (umask 077 && openssl rand -hex 24 | tr -d '\n' >"$1")
  chmod 644 "$1" # read inside the containers by their own non-root users; .run/ itself is 0700
}

# $1 file: a P-256 PKCS#8 key as keycloak-provisioners.sh makes it, created here so that every secret file of
# compose.yaml exists before the first container starts; the provisioners keep and register it.
provisioner_key() {
  [[ -s "$1" ]] || (umask 077 && openssl ecparam -name prime256v1 -genkey -noout | openssl pkcs8 -topk8 -nocrypt -out "$1")
  chmod 644 "$1"
}

up() {
  for tool in docker curl jq openssl; do command -v "$tool" >/dev/null || die "$tool is required"; done
  mkdir -p "$RUN/keys"
  chmod 700 "$RUN" "$RUN/keys"
  for name in db_password db_owner_password db_app_password keycloak_admin_password; do secret "$RUN/$name"; done
  provisioner_key "$RUN/keys/tenancy-provisioner.pem"
  provisioner_key "$RUN/keys/device-provisioner.pem"
  # The BFF's key is a private JWK; keycloak-bff-dev-key.sh fills it once Keycloak answers. An empty placeholder lets
  # Compose resolve the secret before then (the script treats an empty file as missing).
  [[ -e "$RUN/keys/backoffice-bff.jwk" ]] || (umask 077 && : >"$RUN/keys/backoffice-bff.jwk")

  if [[ "${E2E_SKIP_BUILD:-}" != 1 ]]; then
    log "building the POS and the BackOffice"
    # The POS asks the devices realm for its terminal token from the browser (D182, FC §3): a production build has
    # no .env.development, so the issuer is given here, as the deploy build gives its own.
    (cd "$FRONTEND" && VITE_DEVICES_ISSUER="$KEYCLOAK_URL/realms/restaurantpro-devices" pnpm --filter pos build \
      && pnpm --filter backoffice build)
  fi
  [[ -f "$FRONTEND/apps/pos/dist/index.html" && -f "$FRONTEND/apps/backoffice/dist/index.html" ]] \
    || die "no built SPA in frontend/apps/*/dist (run without E2E_SKIP_BUILD=1)"

  log "starting PostgreSQL, Keycloak and Mailpit"
  compose up -d --wait postgres keycloak mailpit
  # Keycloak reaches Mailpit on localhost: they share one network namespace (compose.yaml).
  "$DEPLOY/keycloak-provisioners.sh" --url "$KEYCLOAK_URL" --admin-password-file "$RUN/keycloak_admin_password" \
    --keys-dir "$RUN/keys" --smtp-host localhost:1025
  chmod 700 "$RUN/keys"
  chmod 644 "$RUN"/keys/*.pem
  "$DEPLOY/keycloak-bff-dev-key.sh" --url "$KEYCLOAK_URL" --admin-password-file "$RUN/keycloak_admin_password" \
    --key-file "$RUN/keys/backoffice-bff.jwk" --key-mode 644 --origin "$BACKOFFICE_URL"

  log "building and starting the backend, the BFF and Caddy"
  compose up -d --build --wait backend bff caddy
  log "up: POS $POS_URL, BackOffice $BACKOFFICE_URL, Keycloak $KEYCLOAK_URL, backend $BACKEND_URL"
}

env_vars() {
  cat <<EOF
E2E_POS_URL=$POS_URL
E2E_BACKOFFICE_URL=$BACKOFFICE_URL
E2E_KEYCLOAK_URL=$KEYCLOAK_URL
E2E_BACKEND_URL=$BACKEND_URL
E2E_KEYCLOAK_ADMIN_PASSWORD_FILE=$RUN/keycloak_admin_password
EOF
}

case "${1:-}" in
  up) up ;;
  env) env_vars ;;
  test)
    shift
    [[ -f "$RUN/keycloak_admin_password" ]] || die "no stack: run deploy/e2e/stack.sh up first"
    while IFS= read -r line; do export "${line?}"; done < <(env_vars)
    cd "$FRONTEND" && exec pnpm exec playwright test "$@"
    ;;
  logs) shift; compose logs "$@" ;;
  down)
    compose down --volumes --remove-orphans
    rm -rf "$RUN"
    ;;
  *) die "usage: stack.sh up | env | test [playwright args] | logs [service] | down" ;;
esac
