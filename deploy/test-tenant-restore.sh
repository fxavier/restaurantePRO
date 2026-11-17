#!/usr/bin/env bash
# Tenant-restore drill (R6 S75, A91, D188): one tenant is put back to a point in time without touching another.
#   1. production holds tenants A and B (identical codes); at time T its whole database is copied into a staging
#      instance (pg_dump | pg_restore: the stand-in for pitr-restore.sh, whose PITR itself test-pitr.sh drills);
#   2. after T, an incident corrupts A (a store renamed, the terminal deleted, a stray store added) while B carries on
#      (a new store, a membership revoked);
#   3. A is exported from staging (production mode: age, throwaway keys) — also under D188 fallback (b), the staging
#      owner without BYPASSRLS, which must give the same archive;
#   4. tenant-restore.sh refuses, changing nothing: A still ACTIVE; a tampered archive; a malformed archive name; a key
#      that is not a recipient;
#      another schema version;
#   5. A suspended, the restore replaces A's rows: A equals A at T (every TENANT* table and its memberships), B equals
#      B after its post-T changes, A stays SUSPENDED, and the restore is audited (TENANT_RESTORED).
# Usage: deploy/test-tenant-restore.sh   (needs Docker; leaves nothing behind)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="rp-tenant-restore-$$-$(date +%s)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-tenant-restore-drill.XXXXXX")"
STARTED=()
PROD="$RUN_ID-prod"
STAGING="$RUN_ID-staging"
A=0190b000-0000-7000-8000-00000000000a
B=0190b000-0000-7000-8000-00000000000b

cleanup() {
  local status=$?
  if ((${#STARTED[@]})); then docker rm -f -v "${STARTED[@]}" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: tenant restore drill" >&2; else echo "FAIL: tenant restore drill (exit $status)" >&2; fi
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
die() { fail "$@"; }
step() { echo "--- $*" >&2; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"
# shellcheck source=lib/roles.sh
. "$DEPLOY_DIR/lib/roles.sh"
# shellcheck source=test/keys.sh
. "$DEPLOY_DIR/test/keys.sh"
# shellcheck source=test/tenant-drill.sh
. "$DEPLOY_DIR/test/tenant-drill.sh"

restore() { "$DEPLOY_DIR/tenant-restore.sh" --container "$PROD" "$@"; }
status_of_a() { su_sql "$PROD" "SELECT status FROM tenancy.tenant WHERE id = '$A'"; }
refused() { # $1 what, then the restore arguments: must fail and leave A and B as they are
  local what=$1 a b
  shift
  a="$(fingerprint "$PROD" "$A")"
  b="$(fingerprint "$PROD" "$B")"
  if restore "$@" >"$WORK/refused.out" 2>&1; then fail "$what: the restore was not refused"; fi
  [[ "$(fingerprint "$PROD" "$A")" == "$a" && "$(fingerprint "$PROD" "$B")" == "$b" ]] || fail "$what: a refused restore changed a tenant"
}

step "production with tenants A and B; staging = production at T"
mkdir -m 700 "$WORK/prod-secrets" "$WORK/staging-secrets" "$WORK/keys" "$WORK/out" "$WORK/out-b" "$WORK/tampered"
make_role_secrets "$WORK/prod-secrets"
make_role_secrets "$WORK/staging-secrets"
make_throwaway_keys "$WORK/keys" # production mode from here on (D98)
start_installation "$PROD" "$WORK/prod-secrets"
seed_tenant "$PROD" "$A" tenant-a ACTIVE
seed_tenant "$PROD" "$B" tenant-b ACTIVE
a_at_t="$(fingerprint "$PROD" "$A")"
start_database "$STAGING" "$WORK/staging-secrets"
docker exec "$PROD" pg_dump -U "$DB_USER" -d "$DB" -Fc >"$WORK/at-t.dump"
docker exec -i "$STAGING" pg_restore -U "$DB_USER" -d "$DB" --clean --if-exists --exit-on-error <"$WORK/at-t.dump" 2>"$WORK/restore.log" \
  || { tail -5 "$WORK/restore.log" >&2; fail "staging could not be restored"; }
[[ "$(fingerprint "$STAGING" "$A")" == "$a_at_t" ]] || fail "staging is not production at T"

step "after T: A is corrupted, B carries on"
su_sql "$PROD" "
  UPDATE organization.store SET name = 'CORRUPTED' WHERE tenant_id = '$A' AND code = '001';
  DELETE FROM organization.terminal WHERE tenant_id = '$A';
  INSERT INTO organization.store (id, tenant_id, code, name, timezone, active, version, created_at, updated_at)
    VALUES (gen_random_uuid(), '$A', '999', 'Stray', 'Africa/Maputo', true, 0, now(), now());
  INSERT INTO organization.store (id, tenant_id, code, name, timezone, active, version, created_at, updated_at)
    VALUES (gen_random_uuid(), '$B', '003', 'Matola', 'Africa/Maputo', true, 0, now(), now());
  UPDATE tenancy.tenant_membership SET status = 'REVOKED', revoked_at = now() WHERE tenant_id = '$B';" >/dev/null
b_after_t="$(fingerprint "$PROD" "$B")"
[[ "$(fingerprint "$PROD" "$A")" != "$a_at_t" ]] || fail "the incident did not change A"

step "A exported from staging, under (a) and under fallback (b)"
archive="$("$DEPLOY_DIR/tenant-export.sh" --container "$STAGING" --tenant "$A" --out "$WORK/out")"
su_sql "$STAGING" "ALTER ROLE restaurantpro_owner NOBYPASSRLS" >/dev/null
archive_b="$("$DEPLOY_DIR/tenant-export.sh" --container "$STAGING" --tenant "$A" --out "$WORK/out-b")"
age_decrypt "$WORK/keys/operational.key" <"$archive" >"$WORK/a.tar"
age_decrypt "$WORK/keys/operational.key" <"$archive_b" >"$WORK/b.tar"
[[ "$(manifest_tables "$WORK/a.tar")" == "$(manifest_tables "$WORK/b.tar")" ]] || fail "(b) exports other rows than (a)"

step "refusals change nothing"
refused "A is ACTIVE" --archive "$archive" --identity "$WORK/keys/operational.key"
su_sql "$PROD" "UPDATE tenancy.tenant SET status = 'SUSPENDED', suspended_at = now() WHERE id = '$A'" >/dev/null
mkdir "$WORK/tampered/x"
tar -C "$WORK/tampered/x" -xf "$WORK/a.tar"
sed -i.bak 's/Baixa/Tampered/' "$WORK/tampered/x/organization.store.copy" && rm -f "$WORK/tampered/x/"*.bak
(cd "$WORK/tampered/x" && tar -cf "$WORK/tampered/tenant-$A-20260101T000000Z.tar" MANIFEST ./*.copy)
export RESTAURANTPRO_ENVIRONMENT=test # the tampered tar is plaintext
refused "a tampered archive" --archive "$WORK/tampered/tenant-$A-20260101T000000Z.tar"
grep -q "does not match the MANIFEST" "$WORK/refused.out" || fail "the tampered archive was refused for another reason: $(cat "$WORK/refused.out")"
# G5: an archive name never reaches SQL unchecked (a quote in it would be spliced into the superuser's statement)
cp "$WORK/a.tar" "$WORK/tampered/tenant-x');DROP TABLE organization.store;--.tar"
refused "a malformed archive name" --archive "$WORK/tampered/tenant-x');DROP TABLE organization.store;--.tar"
grep -q "archive name" "$WORK/refused.out" || fail "the malformed name was refused for another reason: $(cat "$WORK/refused.out")"
export RESTAURANTPRO_ENVIRONMENT=production
(umask 077 && age_keygen >"$WORK/keys/other.key")
refused "a key that is not a recipient" --archive "$archive" --identity "$WORK/keys/other.key"
su_sql "$PROD" "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
  VALUES ((SELECT max(installed_rank) + 1 FROM flyway_schema_history), '99999', 'drill', 'SQL', 'drill', 0, 'drill', 0, true)" >/dev/null
refused "another schema version" --archive "$archive" --identity "$WORK/keys/operational.key"
su_sql "$PROD" "DELETE FROM flyway_schema_history WHERE version = '99999'" >/dev/null

step "A suspended: the restore puts A back to T and leaves B alone"
restore --archive "$archive" --identity "$WORK/keys/operational.key"
[[ "$(fingerprint "$PROD" "$A")" == "$a_at_t" ]] || fail "A is not A at T: $(diff <(echo "$a_at_t") <(fingerprint "$PROD" "$A"))"
[[ "$(fingerprint "$PROD" "$B")" == "$b_after_t" ]] || fail "the restore of A changed B"
[[ "$(status_of_a)" == SUSPENDED ]] || fail "A is $(status_of_a) after the restore, not SUSPENDED"
[[ "$(su_sql "$PROD" "SELECT count(*) FROM tenancy.platform_audit_event WHERE tenant_id = '$A' AND action = 'TENANT_RESTORED'")" == 1 ]] \
  || fail "the restore is not audited"
[[ "$(su_sql "$PROD" "SELECT count(*) FROM organization.store WHERE tenant_id = '$A' AND code = '999'")" == 0 ]] || fail "the stray store survived"
