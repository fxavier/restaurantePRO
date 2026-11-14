#!/usr/bin/env bash
# Upgrade drill for the database roles (ADR 0039, D163; acceptance A69, deploy part): an R5 installation, whose
# backend connects as the superuser and whose objects the superuser owns, upgraded to this build, then rolled back.
#   1. builds the R5 backend image from git ref --r5-ref (git archive of backend/ and deploy/) and this checkout's,
#      and starts the R5 deploy/compose.yaml as a throwaway project (test mode, throwaway secrets); the bootstrap
#      writes the company and the admin
#   2. the R5 stack goes down (volumes kept); the operator's upgrade steps (README, "Upgrade to row-level
#      security"): create secrets/db_owner_password and secrets/db_app_password, then `up` with this compose.yaml
#   3. after the upgrade: the backend is healthy, its pool sessions are restaurantpro_app only, it holds no
#      db_password; restaurantpro_owner owns every application object and applied the new migrations; the
#      application role owns nothing, is no superuser, has no BYPASSRLS and no write on the Flyway history (D163,
#      lib/roles.sh); the R5 data is still there
#   4. a second `up` (db-provision runs again) changes no owner or privilege
#   5. the backend refuses to start with a superuser, a BYPASSRLS role or the owner as its pool role
#   6. rollback: the R5 deploy/ and image refuse the migrated database alone (R5's Flyway reports the RLS migrations,
#      below its newest version, as applied but missing, and a stand-in migration above it as future), and start on it with compose.rollback-r5.yaml (README,
#      "Rollback to R5"): health UP, as the superuser, whom row-level security never binds; nothing in the database
#      is undone
# Leaves nothing behind but the two images (a build cache). Every credential is throwaway and never printed.
#
# Usage: deploy/test-upgrade-roles.sh --r5-ref REF     REF = the R5 release (a tag or commit of this repository)
#        Needs Docker and git; ports 38443 and 38080 free; about 5-8 minutes (two image builds, three starts).
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
DB=restaurantpro
DB_USER=restaurantpro
r5_ref=""
while (($#)); do
  case "$1" in
    --r5-ref) r5_ref="${2:?--r5-ref needs a value}"; shift 2 ;;
    -h | --help) awk 'NR > 1 && !/^#/ { exit } NR > 1 { sub(/^# ?/, ""); print }' "$0" >&2; exit 0 ;;
    *) echo "usage: test-upgrade-roles.sh --r5-ref REF" >&2; exit 2 ;;
  esac
done
[[ -n "$r5_ref" ]] || { echo "usage: test-upgrade-roles.sh --r5-ref REF" >&2; exit 2; }

RUN_ID="rp-upgrade-drill-$$-$(date +%s)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-upgrade-drill.XXXXXX")"
R5_IMAGE_TAG=upgrade-drill-r5
NEW_IMAGE_TAG=upgrade-drill

fail() { echo "FAIL: $*" >&2; exit 1; }
die() { fail "$@"; }
ok() { echo "ok: $*" >&2; }
step() { echo "--- $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=lib/roles.sh
. "$DEPLOY_DIR/lib/roles.sh"

r5() { RESTAURANTPRO_VERSION=$R5_IMAGE_TAG docker compose -p "$RUN_ID" -f "$WORK/r5/deploy/compose.yaml" -f "$WORK/override.yaml" "$@"; }
new() { RESTAURANTPRO_VERSION=$NEW_IMAGE_TAG docker compose -p "$RUN_ID" -f "$DEPLOY_DIR/compose.yaml" -f "$WORK/override.yaml" "$@"; }
cleanup() {
  local status=$?
  new down -v --remove-orphans >/dev/null 2>&1 || true
  r5 down -v --remove-orphans >/dev/null 2>&1 || true
  docker ps -aq --filter "label=com.docker.compose.project=$RUN_ID" | xargs docker rm -f -v >/dev/null 2>&1 || true
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: database roles upgrade drill" >&2; else echo "FAIL: database roles upgrade drill (exit $status)" >&2; fi
}
trap cleanup EXIT

psql_in() { docker exec -i "$(new ps -q postgres)" psql -X -q -At -v ON_ERROR_STOP=1 -U "$DB_USER" -d "$DB" "$@"; }
pool_users() { psql_in -c "SELECT coalesce(string_agg(DISTINCT usename, ','), '') FROM pg_stat_activity WHERE application_name = 'PostgreSQL JDBC Driver'"; }
# Owners and ACLs of every application object, the default ACLs and the role attributes (D163's end state).
grants() {
  psql_in <<'SQL'
SELECT 'rel', c.oid::regclass::text, pg_get_userbyid(c.relowner), c.relacl FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'S') AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema' ORDER BY 2;
SELECT 'schema', nspname, pg_get_userbyid(nspowner), nspacl FROM pg_namespace WHERE nspname NOT LIKE 'pg\_%' AND nspname <> 'information_schema' ORDER BY 2;
SELECT 'routine', p.oid::regprocedure::text, pg_get_userbyid(p.proowner), p.proacl FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema' ORDER BY 2;
SELECT 'default', pg_get_userbyid(defaclrole), defaclnamespace::regnamespace::text, defaclobjtype, defaclacl FROM pg_default_acl ORDER BY 2, 3, 4;
SELECT 'role', rolname, rolsuper, rolbypassrls, rolinherit FROM pg_roles WHERE rolname LIKE 'restaurantpro%' ORDER BY 2;
SQL
}
# Starts the backend once with pool role $1 ($2 describes it) and password file $4 (default the app secret), and
# expects the startup check to refuse it because $3.
refused_as() {
  local out status=0
  out="$(timeout 240 env RESTAURANTPRO_VERSION=$NEW_IMAGE_TAG docker compose -p "$RUN_ID" -f "$DEPLOY_DIR/compose.yaml" \
    -f "$WORK/override.yaml" run --rm --no-deps -e RESTAURANTPRO_DB_USERNAME="$1" \
    -e RESTAURANTPRO_DB_PASSWORD_FILE="${4:-/run/secrets/db_app_password}" backend 2>&1)" || status=$?
  ((status != 0)) || fail "the backend started with pool role $1 ($2)"
  grep -q 'refuses to start (row-level security, ADR 0039)' <<<"$out" || fail "pool role $1: not refused by the startup check: $(tail -3 <<<"$out")"
  grep -q "$3" <<<"$out" || fail "pool role $1: refused, but not because $3: $(grep -o 'refuses to start[^"]*' <<<"$out" | head -1)"
  ok "pool role $1 ($2): refused at startup ($3)"
}

step "1. the R5 release ($r5_ref) and this build"
mkdir -p "$WORK/r5" "$WORK/secrets"
git -C "$DEPLOY_DIR/.." archive "$r5_ref" backend deploy | tar -x -C "$WORK/r5" || fail "cannot export $r5_ref"
! grep -q db-provision "$WORK/r5/deploy/compose.yaml" || fail "$r5_ref already provisions the roles: not an R5 release"
age_image_ready
docker build -q -f "$WORK/r5/deploy/Dockerfile" --build-context deploy="$WORK/r5/deploy" -t "restaurantpro-backend:$R5_IMAGE_TAG" \
  "$WORK/r5/backend" >/dev/null || fail "the R5 backend image did not build"
docker build -q -f "$DEPLOY_DIR/Dockerfile" --build-context deploy="$DEPLOY_DIR" -t "restaurantpro-backend:$NEW_IMAGE_TAG" \
  "$DEPLOY_DIR/../backend" >/dev/null || fail "this build's backend image did not build"
for f in db_password admin_initial_password metrics_token; do openssl rand -hex 24 >"$WORK/secrets/$f"; done
openssl rand -base64 32 | tr -d '\n' >"$WORK/secrets/mfa_key"
: >"$WORK/secrets/oidc_client_secret"
chmod 755 "$WORK/secrets"
chmod 644 "$WORK/secrets"/*
{
  echo "secrets:"
  for f in db_password db_owner_password db_app_password admin_initial_password mfa_key metrics_token oidc_client_secret; do
    printf '  %s:\n    file: %s\n' "$f" "$WORK/secrets/$f"
  done
} >"$WORK/override.yaml"
export RESTAURANTPRO_ENVIRONMENT=test BACKUP_AGE_RECIPIENTS_FILE=/dev/null RESTAURANTPRO_HOSTNAME=upgrade-drill.invalid \
  RESTAURANTPRO_HTTPS_PORT=38443 RESTAURANTPRO_HTTP_PORT=38080 RESTAURANTPRO_BOOTSTRAP_COMPANY_NAME="Upgrade Drill, Lda" \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_TAX_ID=UPGRADE-1 RESTAURANTPRO_BOOTSTRAP_COMPANY_COUNTRY=MZ \
  RESTAURANTPRO_BOOTSTRAP_COMPANY_CURRENCY=MZN RESTAURANTPRO_BOOTSTRAP_ADMIN_USERNAME=admin RESTAURANTPRO_FISCAL_ALLOW_NON_CERTIFIED=true
r5 up -d --no-build --wait --wait-timeout 300 >"$WORK/r5-up.log" 2>&1 || { tail -20 "$WORK/r5-up.log" >&2; fail "the R5 stack did not become healthy"; }
[[ "$(pool_users)" == "$DB_USER" ]] || fail "the R5 backend's sessions are '$(pool_users)', expected the superuser"
[[ "$(psql_in -c "SELECT count(*) FROM pg_roles WHERE rolname IN ('restaurantpro_owner', 'restaurantpro_app')")" == 0 ]] \
  || fail "the R5 database already has the roles"
company="$(psql_in -c 'SELECT name FROM organization.company_profile')"
[[ "$company" == "Upgrade Drill, Lda" ]] || fail "the R5 bootstrap did not write the company (got '$company')"
r5_head="$(psql_in -c 'SELECT max(installed_rank) FROM flyway_schema_history')"
ok "R5 ($r5_ref) up: the backend connects as the superuser, who owns everything; company bootstrapped; $r5_head Flyway rows"

step "2. upgrade: the two role secrets, then up with this compose.yaml"
r5 down >/dev/null 2>&1 || fail "the R5 stack did not stop"
make_role_secrets "$WORK/secrets"
new up -d --no-build --wait --wait-timeout 300 >"$WORK/up.log" 2>&1 || {
  tail -20 "$WORK/up.log" >&2; new logs --no-color --tail 30 db-provision backend >&2 || true; fail "the upgraded stack did not become healthy"; }

step "3. after the upgrade"
[[ "$(pool_users)" == restaurantpro_app ]] || fail "the upgraded backend's sessions are '$(pool_users)', not restaurantpro_app only"
assert_roles "$(new ps -q postgres)" "$DB" "after the upgrade"
if docker exec "$(new ps -q backend)" test -e /run/secrets/db_password; then fail "the upgraded backend holds db_password"; fi
[[ "$(psql_in -c 'SELECT name FROM organization.company_profile')" == "$company" ]] || fail "the R5 data is gone"
installers="$(psql_in -c "SELECT string_agg(DISTINCT installed_by, ',') FROM flyway_schema_history WHERE installed_rank > $r5_head")"
[[ "$installers" == restaurantpro_owner ]] || fail "the new migrations were applied by '$installers', not restaurantpro_owner"
[[ "$(psql_in -c "SELECT count(*) FROM flyway_schema_history WHERE installed_rank > $r5_head AND success")" -gt 0 ]] \
  || fail "no new migration was applied"
ok "backend healthy as restaurantpro_app (no db_password); roles as D163; new migrations applied by restaurantpro_owner; R5 data kept"

step "4. a second up changes no owner or privilege"
before="$(grants)"
provisioned_at() { docker inspect -f '{{.State.StartedAt}} {{.State.ExitCode}}' "$(new ps -aq db-provision)"; }
first_run="$(provisioned_at)"
new stop backend >/dev/null 2>&1
new up -d --no-build --wait --wait-timeout 300 >>"$WORK/up.log" 2>&1 || fail "the second up failed"
second_run="$(provisioned_at)"
[[ "$second_run" != "$first_run" && "$second_run" == *" 0" ]] || fail "db-provision did not run again successfully ($first_run / $second_run)"
after="$(grants)"
if [[ "$after" != "$before" ]]; then diff <(echo "$before") <(echo "$after") >&2 || true; fail "the second up changed owners or privileges"; fi
ok "db-provision ran again; $(wc -l <<<"$before" | tr -d ' ') owner/privilege lines unchanged"

step "5. the backend refuses a superuser, a BYPASSRLS role and the owner as its pool"
{ # throwaway roles with the app role's password, on stdin
  printf "\\\\set pw '%s'\n" "$(cat "$WORK/secrets/db_app_password")"
  printf '%s\n' "CREATE ROLE drill_superuser LOGIN SUPERUSER PASSWORD :'pw';" "CREATE ROLE drill_bypassrls LOGIN BYPASSRLS PASSWORD :'pw';" \
    'GRANT restaurantpro_app TO drill_bypassrls;'
} | psql_in
new stop backend >/dev/null 2>&1
refused_as drill_superuser "a superuser" "is a superuser"
refused_as drill_bypassrls "BYPASSRLS" "has BYPASSRLS"
refused_as restaurantpro_owner "the owner" "owns" /run/secrets/db_owner_password
psql_in -c 'DROP ROLE drill_superuser; DROP ROLE drill_bypassrls;'
new up -d --no-build --wait --wait-timeout 300 >>"$WORK/up.log" 2>&1 || fail "the stack did not come back"
[[ "$(pool_users)" == restaurantpro_app ]] || fail "after the refusals the backend's sessions are '$(pool_users)'"
ok "the stack is back, as restaurantpro_app"

step "6. rollback: the R5 deploy/ and image on the migrated database"
# A later release's migration above R5's newest version (S69's V14006 over R5's V14005) is FUTURE to R5, not MISSING:
# a stand-in row makes the rollback face both kinds, as on an installation that took the whole RLS run.
psql_in -c "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by,
              execution_time, success)
            SELECT max(installed_rank) + 1, '99999', 'future stand-in', 'SQL', 'V99999__future_stand_in.sql', NULL,
                   'restaurantpro_owner', 0, true FROM flyway_schema_history" >/dev/null
new down >/dev/null 2>&1 || fail "the upgraded stack did not stop"
if r5 up -d --no-build --wait --wait-timeout 180 >"$WORK/rollback.log" 2>&1; then
  fail "the R5 release started without compose.rollback-r5.yaml: the drill's premise changed, review the README rollback"
fi
r5 logs --no-color backend >"$WORK/rollback-refused.log" 2>&1 || true
grep -q 'FlywayValidateException' "$WORK/rollback-refused.log" \
  || fail "the R5 release failed on the migrated database for another reason: $(grep -o 'Caused by: [^\\"]\{0,200\}' "$WORK/rollback-refused.log" | head -2)"
ok "without the override, R5's Flyway refuses the migrated database: $(grep -o 'not resolved locally[^\\"]\{0,40\}' "$WORK/rollback-refused.log" | head -1)"
r5 down >/dev/null 2>&1
rollback() { r5 -f "$DEPLOY_DIR/compose.rollback-r5.yaml" "$@"; }
if ! rollback up -d --no-build --wait --wait-timeout 300 >"$WORK/rollback.log" 2>&1; then
  tail -10 "$WORK/rollback.log" >&2
  rollback logs --no-color backend 2>&1 | grep -o 'Caused by: [^\\"]\{0,240\}' | sort -u | head -5 >&2 || true
  fail "the R5 release does not start on the migrated database with compose.rollback-r5.yaml"
fi
[[ "$(pool_users)" == "$DB_USER" ]] || fail "the rolled-back backend's sessions are '$(pool_users)', expected the superuser"
[[ "$(psql_in -c 'SELECT name FROM organization.company_profile')" == "$company" ]] || fail "the data is gone after the rollback"
ok "R5 starts on the migrated database as the superuser (row-level security does not bind it); health UP; data kept"
