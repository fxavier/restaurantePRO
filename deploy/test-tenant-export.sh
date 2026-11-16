#!/usr/bin/env bash
# Tenant export drill (R6 S75, A91, D188). On a throwaway installation (the real migrations, the D163 roles) holding
# tenants A and B with IDENTICAL store and terminal codes:
#   - test mode: tenant-export.sh A writes a tar whose MANIFEST lists exactly the TENANT* tables (+ tenancy.tenant and
#     its memberships), whose files match the MANIFEST, hold A's rows (the counts of the database) and nothing of B
#     (no B tenant id, no B row id anywhere); B and A are untouched;
#   - production mode (throwaway age keys): the archive is age ciphertext with its sidecar, no plaintext is left in
#     the output directory, and it decrypts with the operational key to the same tables and checksums;
#   - D188 fallback (b): with the owner stripped of BYPASSRLS (tables keep FORCE ROW LEVEL SECURITY) the owner reads
#     nothing unbound (RS003), and the export, which binds the tenant, gives the same tables and checksums as (a);
#   - an unknown tenant or a malformed id is refused, writing nothing.
# Usage: deploy/test-tenant-export.sh   (needs Docker; leaves nothing behind)
set -euo pipefail

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="rp-tenant-export-$$-$(date +%s)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-tenant-export-drill.XXXXXX")"
STARTED=()
SRC="$RUN_ID-src"
A=0190b000-0000-7000-8000-00000000000a
B=0190b000-0000-7000-8000-00000000000b

cleanup() {
  local status=$?
  if ((${#STARTED[@]})); then docker rm -f -v "${STARTED[@]}" >/dev/null 2>&1 || true; fi
  rm -rf "$WORK"
  if ((status == 0)); then echo "PASS: tenant export drill" >&2; else echo "FAIL: tenant export drill (exit $status)" >&2; fi
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

export_as() { # $1 mode (test|production), $2 out dir, then tenant-export.sh arguments
  local mode=$1 dir=$2
  shift 2
  RESTAURANTPRO_ENVIRONMENT="$mode" "$DEPLOY_DIR/tenant-export.sh" --container "$SRC" --out "$dir" "$@"
}

# $1 plaintext tar: the export of A, as the database holds it, and nothing of B.
check_export_of_a() {
  local tar=$1 x="$WORK/x-$RANDOM" table rows sum f b_id
  mkdir "$x"
  tar -C "$x" -xf "$tar"
  [[ "$(sed -n 's/^tenant //p' "$x/MANIFEST")" == "$A" ]] || fail "the MANIFEST does not name tenant A"
  [[ "$(sed -n 's/^table \([^ ]*\) .*/\1/p' "$x/MANIFEST" | LC_ALL=C sort)" == "$(tenant_tables "$SRC")" ]] \
    || fail "the MANIFEST does not list exactly the TENANT* tables and the tenancy rows"
  while read -r _ table rows sum; do
    f="$x/$table.copy"
    [[ "$(wc -l <"$f" | tr -d ' ')" == "$rows" && "$(sha256_hex "$f")" == "$sum" ]] || fail "$table.copy does not match the MANIFEST"
    if [[ "$table" == tenancy.tenant ]]; then
      [[ "$rows" == 1 ]] || fail "tenancy.tenant: $rows rows, expected A's one"
    else
      [[ "$rows" == "$(su_sql "$SRC" "SELECT count(*) FROM $table WHERE tenant_id = '$A'")" ]] || fail "$table: $rows rows, the database holds another count for A"
    fi
  done < <(grep '^table ' "$x/MANIFEST")
  [[ "$(grep -c . "$x/organization.store.copy")" == 2 ]] || fail "A's two stores are not exported"
  ! grep -rq "$B" "$x" || fail "the export of A holds tenant B's id"
  for b_id in $(su_sql "$SRC" "SELECT id FROM organization.store WHERE tenant_id = '$B' UNION ALL SELECT id FROM organization.terminal WHERE tenant_id = '$B'"); do
    ! grep -rq "$b_id" "$x" || fail "the export of A holds B's row $b_id"
  done
}

step "an installation with tenants A and B (identical store and terminal codes)"
mkdir -m 700 "$WORK/secrets" "$WORK/keys" "$WORK/out-test" "$WORK/out-prod" "$WORK/out-b" "$WORK/out-refused"
make_role_secrets "$WORK/secrets"
start_installation "$SRC" "$WORK/secrets"
seed_tenant "$SRC" "$A" tenant-a ACTIVE
seed_tenant "$SRC" "$B" tenant-b ACTIVE
before_a="$(fingerprint "$SRC" "$A")"
before_b="$(fingerprint "$SRC" "$B")"

step "test mode: a plaintext archive of A only"
plain="$(export_as test "$WORK/out-test" --tenant "$A")"
[[ -f "$plain" && "$plain" == *.tar ]] || fail "no plaintext archive: $plain"
check_export_of_a "$plain"
[[ "$(fingerprint "$SRC" "$A")" == "$before_a" && "$(fingerprint "$SRC" "$B")" == "$before_b" ]] || fail "the export changed a tenant"

step "production mode: age ciphertext with its sidecar, decrypting to the same archive"
make_throwaway_keys "$WORK/keys"
sealed="$(export_as production "$WORK/out-prod" --tenant "$A")"
[[ "$sealed" == *.tar.age ]] && is_age_file "$sealed" || fail "the production archive is not age ciphertext"
is_sidecar "${sealed%.age}.sha256" || fail "no well-formed sidecar"
[[ "$(find "$WORK/out-prod" -type f ! -name '*.age' ! -name '*.sha256' | wc -l | tr -d ' ')" == 0 ]] || fail "plaintext left in the output directory"
age_decrypt "$WORK/keys/operational.key" <"$sealed" >"$WORK/decrypted.tar"
[[ "$(manifest_tables "$WORK/decrypted.tar")" == "$(manifest_tables "$plain")" ]] || fail "the decrypted archive differs"
age_decrypt "$WORK/keys/recovery.key" <"$sealed" >/dev/null || fail "the recovery key cannot decrypt the archive"

step "D188 fallback (b): the owner without BYPASSRLS"
su_sql "$SRC" "ALTER ROLE restaurantpro_owner NOBYPASSRLS" >/dev/null
if owner_sql "$SRC" "SELECT count(*) FROM organization.store" >"$WORK/unbound.out" 2>&1; then
  fail "under (b) the owner read a tenant table unbound: $(cat "$WORK/unbound.out")"
fi
grep -q "RS003\|tenant" "$WORK/unbound.out" || fail "under (b) the unbound read failed for another reason: $(cat "$WORK/unbound.out")"
fallback="$(export_as test "$WORK/out-b" --tenant "$A")"
check_export_of_a "$fallback"
[[ "$(manifest_tables "$fallback")" == "$(manifest_tables "$plain")" ]] || fail "(b) exports other rows than (a)"
su_sql "$SRC" "ALTER ROLE restaurantpro_owner BYPASSRLS" >/dev/null

step "refusals write nothing"
if export_as test "$WORK/out-refused" --tenant "$(uuidgen | tr 'A-Z' 'a-z')" >/dev/null 2>&1; then fail "an unknown tenant was exported"; fi
if export_as test "$WORK/out-refused" --tenant "not-a-uuid" >/dev/null 2>&1; then fail "a malformed tenant id was accepted"; fi
[[ -z "$(ls -A "$WORK/out-refused")" ]] || fail "a refused export wrote a file"
