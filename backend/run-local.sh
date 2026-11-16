#!/usr/bin/env bash
# Local development run: starts the dev database (compose.yaml), provisions its roles and starts the backend with dev
# settings, as restaurantpro_app (pool) and restaurantpro_owner (Flyway), never as the superuser (ADR 0039).
# Usage, from backend/:  ./run-local.sh
# Override any value by exporting it first, e.g. RESTAURANTPRO_DB_PASSWORD=other ./run-local.sh
# Dev only: a non-certified fiscal adapter, throwaway database and admin passwords. Never use for production (see deploy/).
set -euo pipefail
cd "$(dirname "$0")"

# The superuser keeps the dev password a dev volume was created with before ADR 0039, so that volume is upgraded in
# place (provisioning hands its objects to restaurantpro_owner). The two role passwords are re-set at every start.
export RESTAURANTPRO_DEV_SUPERUSER_PASSWORD="${RESTAURANTPRO_DEV_SUPERUSER_PASSWORD:-devpass}"
export RESTAURANTPRO_FLYWAY_PASSWORD="${RESTAURANTPRO_FLYWAY_PASSWORD:-devpass-owner}"
export RESTAURANTPRO_DB_PASSWORD="${RESTAURANTPRO_DB_PASSWORD:-devpass-app}"
# R6 (S75): the dev Keycloak (compose.yaml) and its own database.
export RESTAURANTPRO_DEV_KEYCLOAK_DB_PASSWORD="${RESTAURANTPRO_DEV_KEYCLOAK_DB_PASSWORD:-devpass-keycloak-db}"
export RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD="${RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD:-devpass-keycloak-admin}"
export RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED="${RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED:-true}"
export RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME="${RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME:-Teste Lda}"
export RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID="${RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID:-123456789}"
export RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY="${RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY:-MZ}"
export RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY="${RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY:-MZN}"
export RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME="${RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME:-admin}"
# Swagger UI and /v3/api-docs load before login (local only; data endpoints still need a session).
export RESTAURANTPRO_DEV_OPEN_API_DOCS="${RESTAURANTPRO_DEV_OPEN_API_DOCS:-true}"

admin_pw_file="${TMPDIR:-/tmp}/restaurantpro-dev-admin-pw"
[ -f "$admin_pw_file" ] || printf '%s' 'ChangeMe-123' > "$admin_pw_file"
export RESTAURANTPRO_BOOTSTRAP_ADMIN_INITIAL_PASSWORD_FILE="$admin_pw_file"

# db-provision (compose.yaml) logs in as the superuser over the network, so it is what notices a dev volume created
# with another superuser password; fail early with a clear hint. (A bare `docker compose up -d` runs it too.)
docker compose up -d --wait postgres
if ! provision_log="$(docker compose run --rm db-provision 2>&1)"; then
  if grep -q 'password authentication failed' <<<"$provision_log"; then
    echo "The dev database rejects RESTAURANTPRO_DEV_SUPERUSER_PASSWORD (before ADR 0039: RESTAURANTPRO_DB_PASSWORD)." >&2
    echo "Use the password it was created with, or reset it (deletes local dev data only):" >&2
    echo "  docker compose down -v && ./run-local.sh" >&2
  else
    printf '%s\n' "$provision_log" | tail -5 >&2
    echo "Provisioning the dev database roles failed (see above)." >&2
  fi
  exit 1
fi

# R6 (S75): Keycloak with the three realms, then the backend's two provisioners (keys outside the repository).
docker compose up -d --wait keycloak mailpit
keycloak_url="http://localhost:8180"
keycloak_keys="${TMPDIR:-/tmp}/restaurantpro-dev-keycloak-keys"
keycloak_admin_pw_file="$(mktemp)"
trap 'rm -f "$keycloak_admin_pw_file"' EXIT
printf '%s' "$RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD" >"$keycloak_admin_pw_file"
../deploy/keycloak-provisioners.sh --url "$keycloak_url" --admin-password-file "$keycloak_admin_pw_file" \
  --keys-dir "$keycloak_keys" --smtp-host mailpit:1025
rm -f "$keycloak_admin_pw_file"
export RESTAURANTPRO_OIDC_TENANT_ISSUER="${RESTAURANTPRO_OIDC_TENANT_ISSUER:-$keycloak_url/realms/restaurantpro}"
export RESTAURANTPRO_OIDC_OPS_ISSUER="${RESTAURANTPRO_OIDC_OPS_ISSUER:-$keycloak_url/realms/restaurantpro-ops}"
export RESTAURANTPRO_KEYCLOAK_ADMIN_BASE_URL="${RESTAURANTPRO_KEYCLOAK_ADMIN_BASE_URL:-$keycloak_url}"
export RESTAURANTPRO_KEYCLOAK_ADMIN_KEY_FILE="${RESTAURANTPRO_KEYCLOAK_ADMIN_KEY_FILE:-$keycloak_keys/tenancy-provisioner.pem}"
export RESTAURANTPRO_KEYCLOAK_DEVICESADMIN_BASEURL="${RESTAURANTPRO_KEYCLOAK_DEVICESADMIN_BASEURL:-$keycloak_url}"
export RESTAURANTPRO_KEYCLOAK_DEVICESADMIN_KEYFILE="${RESTAURANTPRO_KEYCLOAK_DEVICESADMIN_KEYFILE:-$keycloak_keys/device-provisioner.pem}"
echo "Keycloak: $keycloak_url (admin / \$RESTAURANTPRO_DEV_KEYCLOAK_ADMIN_PASSWORD); mail: http://localhost:8025"

echo "Swagger UI: http://localhost:8080/swagger-ui/index.html  (first login: admin / ChangeMe-123, then change the password)"
exec ./mvnw spring-boot:run
