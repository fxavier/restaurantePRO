#!/usr/bin/env bash
# Local development run: starts the dev database (compose.yaml) and the backend with dev settings.
# Usage, from backend/:  ./run-local.sh
# Override any value by exporting it first, e.g. RESTAURANTPRO_DB_PASSWORD=other ./run-local.sh
# Dev only: a non-certified fiscal adapter and a throwaway admin password. Never use for production (see deploy/).
set -euo pipefail
cd "$(dirname "$0")"

export RESTAURANTPRO_DB_PASSWORD="${RESTAURANTPRO_DB_PASSWORD:-devpass}"
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

docker compose up -d --wait

# The dev volume keeps the password it was created with; fail early with a clear hint if it differs.
if ! docker compose exec -T -e PGPASSWORD="$RESTAURANTPRO_DB_PASSWORD" postgres \
     psql -h 127.0.0.1 -U restaurantpro -d restaurantpro -c 'select 1' >/dev/null 2>&1; then
  echo "The dev database rejects RESTAURANTPRO_DB_PASSWORD." >&2
  echo "Use the password it was created with, or reset it (deletes local dev data only):" >&2
  echo "  docker compose down -v && ./run-local.sh" >&2
  exit 1
fi

echo "Swagger UI: http://localhost:8080/swagger-ui/index.html  (first login: admin / ChangeMe-123, then change the password)"
exec ./mvnw spring-boot:run
