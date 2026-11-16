# shellcheck shell=bash
# Shared by test-tenant-export.sh and test-tenant-restore.sh (R6 S75, A91). Sourced, never run. The caller defines
# fail(), die(), step(), DEPLOY_DIR, RUN_ID, WORK and STARTED (containers to remove), and sources lib/common.sh.
# An "installation" here is a throwaway PostgreSQL 18.6 provisioned as production is (postgres/provision.sh: the
# D163 roles) and migrated with the real Flyway migrations as restaurantpro_owner (Flyway CLI image, the backend's
# settings: recursive locations, out of order). Bash 3.2 compatible.

PG_IMAGE="postgres:18.6-alpine" # D4: the pin of deploy/compose.yaml and the backend tests
FLYWAY_IMAGE="flyway/flyway:12-alpine"
DB=restaurantpro
DB_USER=restaurantpro
MIGRATIONS="$DEPLOY_DIR/../backend/src/main/resources/db/migration"

ip_of() { docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' "$1"; }
su_sql() { docker exec -i "$1" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" -c "$2"; }
owner_sql() { docker exec -i "$1" psql -X -q -At -v ON_ERROR_STOP=1 -U restaurantpro_owner -d "$DB" -c "$2"; }

# $1 container name, $2 secrets dir (make_role_secrets): an empty provisioned database with no schema.
start_database() {
  local name=$1 secrets=$2 _
  POSTGRES_PASSWORD="$(openssl rand -hex 16)" docker run -d --name "$name" \
    -e POSTGRES_PASSWORD -e POSTGRES_USER="$DB_USER" -e POSTGRES_DB="$DB" \
    --label restaurantpro.drill="$RUN_ID" "$PG_IMAGE" >/dev/null
  STARTED+=("$name")
  for _ in $(seq 1 60); do
    if docker exec "$name" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB"; then break; fi
    sleep 1
  done
  docker exec "$name" pg_isready -q -h 127.0.0.1 -U "$DB_USER" -d "$DB" || fail "$name did not become ready"
  "$DEPLOY_DIR/postgres/provision.sh" --container "$name" --secrets "$secrets" >/dev/null 2>&1 \
    || fail "provisioning the roles of $name failed"
}

# $1 container, $2 secrets dir: the real migrations as the owner. The password goes through an env file (0600).
migrate() {
  local name=$1 secrets=$2 env="$WORK/flyway-$1.env"
  printf 'FLYWAY_PASSWORD=%s\n' "$(tr -d '\n' <"$secrets/db_owner_password")" >"$env"
  docker run --rm --env-file "$env" -v "$MIGRATIONS:/flyway/sql:ro" "$FLYWAY_IMAGE" \
    -url="jdbc:postgresql://$(ip_of "$name"):5432/$DB?sslmode=disable" -user=restaurantpro_owner \
    -locations=filesystem:/flyway/sql -outOfOrder=true migrate >"$WORK/flyway-$1.log" 2>&1 \
    || { tail -20 "$WORK/flyway-$1.log" >&2; fail "Flyway could not migrate $name"; }
  rm -f "$env"
}

start_installation() { start_database "$1" "$2"; migrate "$1" "$2"; }

# $1 container, $2 tenant id, $3 code, $4 status: the tenant, its company profile, two stores with the SAME codes
# in every tenant (001, 002), a terminal T1 in store 001 and one ACCOUNT membership (as the superuser).
seed_tenant() {
  local name=$1 t=$2 code=$3 status=$4
  su_sql "$name" "
    INSERT INTO tenancy.tenant (id, code, legal_name, status, created_at) VALUES ('$t', '$code', 'Company $code', '$status', now());
    INSERT INTO organization.company_profile (tenant_id, id, name, tax_id, country, currency, version, created_at, updated_at)
      VALUES ('$t', gen_random_uuid(), 'Company $code', '400$RANDOM', 'MZ', 'MZN', 0, now(), now());
    INSERT INTO organization.store (id, tenant_id, code, name, timezone, active, version, created_at, updated_at)
      VALUES (gen_random_uuid(), '$t', '001', 'Baixa', 'Africa/Maputo', true, 0, now(), now()),
             (gen_random_uuid(), '$t', '002', 'Sommerschield', 'Africa/Maputo', true, 0, now(), now());
    INSERT INTO organization.terminal (id, tenant_id, store_id, code, name, status, version, created_at, updated_at)
      SELECT gen_random_uuid(), '$t', s.id, 'T1', 'Till', 'REGISTERED', 0, now(), now()
      FROM organization.store s WHERE s.tenant_id = '$t' AND s.code = '001';
    INSERT INTO tenancy.tenant_membership (id, tenant_id, issuer, subject, kind, principal_id, status, valid_from, created_at)
      VALUES (gen_random_uuid(), '$t', 'https://auth.example/realms/restaurantpro', 'user-$code', 'ACCOUNT',
              gen_random_uuid(), 'ACTIVE', now(), now());" >/dev/null || fail "seeding tenant $code in $name failed"
}

# The tables a tenant export covers on $1: every TENANT* table and the two tenancy tables.
tenant_tables() { { su_sql "$1" "SELECT table_name FROM shared.rls_tenant_table"; printf 'tenancy.tenant\ntenancy.tenant_membership\n'; } | LC_ALL=C sort; }

# $1 container, $2 tenant: one line per table, "<table> <rows> <md5 of the sorted rows>", as the superuser. The
# tenancy.tenant row is left out: its status is what an operator changes around a restore.
fingerprint() {
  local name=$1 t=$2 table
  for table in $(tenant_tables "$name"); do
    [[ "$table" == tenancy.tenant ]] && continue
    printf '%s %s\n' "$table" "$(su_sql "$name" "SELECT count(*) || ' ' || coalesce(md5(string_agg(x::text, '|' ORDER BY x::text)), '-') FROM $table x WHERE tenant_id = '$t'")"
  done
}

# The "table <name> <rows> <sha256>" lines of an archive's MANIFEST ($1 = a tar), sorted.
manifest_tables() { tar -xOf "$1" MANIFEST | grep '^table ' | LC_ALL=C sort; }
