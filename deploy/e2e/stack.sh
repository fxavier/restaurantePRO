#!/usr/bin/env bash
# The end-to-end stack of the frontend (ticket 05, ADR 0041, ADR 0043): compose.yaml next to this file, Compose project
# $E2E_PROJECT (restaurantpro-e2e). Throwaway and loopback-only; never point it at shared or production data.
#
#   deploy/e2e/stack.sh up      secrets and keys in deploy/e2e/.run/<project>/ (git-ignored, 0700), builds both SPAs
#                               (skip with E2E_SKIP_BUILD=1) and copies them there, starts PostgreSQL, Keycloak and
#                               Mailpit, registers the backend's
#                               provisioner keys (keycloak-provisioners.sh) and a dev-only key and the e2e origin for
#                               backoffice-bff (keycloak-bff-dev-key.sh), then the backend, the BFF and Caddy
#   deploy/e2e/stack.sh env     the E2E_* variables Playwright needs (no secret: the admin password stays in its file)
#   deploy/e2e/stack.sh test [playwright args]   runs Playwright from frontend/ against the running stack
#   deploy/e2e/stack.sh logs    the services' logs
#   deploy/e2e/stack.sh down    removes the containers, the throwaway data and .run/<project>/ (only this project's)
#
# Several stacks side by side (FE-F20): give each its own E2E_PROJECT and ports, on every command of that stack
# (up, env, test, logs, down). This file is the one source of the defaults; compose.yaml and the Caddyfile require them.
#   E2E_PROJECT=restaurantpro-e2e   E2E_POS_PORT=14173   E2E_BACKOFFICE_PORT=14174   E2E_KEYCLOAK_PORT=18180
#   E2E_BACKEND_PORT=18080          E2E_MAILPIT_PORT=18025
# The POS, BackOffice and Keycloak ports are also the ports the services listen on inside the stack's one network
# namespace (the browser and the services must mean the same localhost:<port>), so they must not be one of the
# namespace's internal ports (5432, 8080, 8081, 8443, 9000, 1025, 8025). E2E_SKIP_IMAGE_BUILD=1 starts the backend
# and BFF images already built (CI builds them with the GitHub Actions layer cache, FE-F31).
#
# Every secret is generated per stack (openssl rand), lives in .run/ only, and is never printed. Needs docker (with
# Compose v2), curl, jq, openssl, and pnpm for the build.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEPLOY="$(cd "$HERE/.." && pwd)"
FRONTEND="$(cd "$HERE/../../frontend" && pwd)"

die() { echo "e2e-stack: $*" >&2; exit 1; }
log() { echo "e2e-stack: $*" >&2; }

# The one source of the stack's name and ports (FE-F20); compose.yaml and the Caddyfile read them from here.
export E2E_PROJECT="${E2E_PROJECT:-restaurantpro-e2e}"
export E2E_POS_PORT="${E2E_POS_PORT:-14173}"
export E2E_BACKOFFICE_PORT="${E2E_BACKOFFICE_PORT:-14174}"
export E2E_KEYCLOAK_PORT="${E2E_KEYCLOAK_PORT:-18180}"
export E2E_BACKEND_PORT="${E2E_BACKEND_PORT:-18080}"
export E2E_MAILPIT_PORT="${E2E_MAILPIT_PORT:-18025}"
[[ "$E2E_PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die "E2E_PROJECT must be a Compose project name (a-z, 0-9, _ and -)"
for port in "$E2E_POS_PORT" "$E2E_BACKOFFICE_PORT" "$E2E_KEYCLOAK_PORT" "$E2E_BACKEND_PORT" "$E2E_MAILPIT_PORT"; do
  [[ "$port" =~ ^[1-9][0-9]{0,4}$ ]] && ((port <= 65535)) || die "not a port: $port"
done
for port in "$E2E_POS_PORT" "$E2E_BACKOFFICE_PORT" "$E2E_KEYCLOAK_PORT"; do
  case "$port" in 5432 | 8080 | 8081 | 8443 | 9000 | 1025 | 8025) die "port $port is taken inside the stack's namespace" ;; esac
done
[[ $(printf '%s\n' "$E2E_POS_PORT" "$E2E_BACKOFFICE_PORT" "$E2E_KEYCLOAK_PORT" "$E2E_BACKEND_PORT" "$E2E_MAILPIT_PORT" \
  | sort -u | wc -l) -eq 5 ]] || die "the five ports must differ"

RUN="$HERE/.run/$E2E_PROJECT"
export E2E_RUN_DIR="$RUN"
KEYCLOAK_URL=http://localhost:$E2E_KEYCLOAK_PORT
BACKEND_URL=http://localhost:$E2E_BACKEND_PORT
POS_URL=http://localhost:$E2E_POS_PORT
BACKOFFICE_URL=http://localhost:$E2E_BACKOFFICE_PORT

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
  chmod 700 "$HERE/.run" "$RUN" "$RUN/keys"
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
  # Caddy serves this stack's copy, so another stack's build (another Keycloak port in the POS) cannot change it.
  rm -rf "$RUN/srv" && mkdir -p "$RUN/srv"
  cp -R "$FRONTEND/apps/pos/dist" "$RUN/srv/pos"
  cp -R "$FRONTEND/apps/backoffice/dist" "$RUN/srv/backoffice"

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
  if [[ "${E2E_SKIP_IMAGE_BUILD:-}" == 1 ]]; then
    docker image inspect restaurantpro-backend:e2e restaurantpro-bff:e2e >/dev/null \
      || die "E2E_SKIP_IMAGE_BUILD=1 but restaurantpro-backend:e2e or restaurantpro-bff:e2e is not built"
    compose up -d --wait backend bff caddy
  else
    compose up -d --build --wait backend bff caddy
  fi
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
    rmdir "$HERE/.run" 2>/dev/null || true # the last stack gone
    ;;
  *) die "usage: stack.sh up | env | test [playwright args] | logs [service] | down" ;;
esac
