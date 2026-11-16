#!/usr/bin/env bash
# Tenant-level restore (D188): the last step of the procedure "restore the cluster to a point in time into a staging
# instance (pitr-restore.sh), export that tenant there (tenant-export.sh), replace the tenant's rows in production".
# This script replaces, in ONE maintenance transaction on the target, every row of the tenant in every TENANT* table
# and its memberships with the archive's; no other tenant's row is read or written. The tenant must be SUSPENDED
# (POST /api/v1/platform/tenants/{id}/suspend), so no request or job of it runs meanwhile; it stays SUSPENDED (an
# operator reactivates it after checking). The tenancy.tenant row itself (status, Keycloak organization) is kept.
#
# It runs as the operations superuser over the container's local socket, because the replacement must delete and
# re-insert confirmed facts of that tenant: session_replication_role = replica (SET LOCAL) switches off the
# append-only and foreign-key triggers for this transaction only. Every table's row count is checked against the
# MANIFEST before COMMIT; any mismatch rolls the whole transaction back. The restore is audited in
# tenancy.platform_audit_event (TENANT_RESTORED).
#
# Refuses, changing nothing: an archive whose files do not match its MANIFEST (or, in production, whose ciphertext
# does not match its sidecar or does not decrypt with --identity); a MANIFEST whose tables are not exactly the
# target's TENANT* tables; another schema version; an unknown tenant; a tenant that is not SUSPENDED.
#
# Usage: deploy/tenant-restore.sh --archive FILE --container NAME [--db NAME] [--identity KEY_FILE]
#                                 [--other-installation PROJECT]
#   production: FILE is tenant-<id>-<UTC>.tar.age with its .sha256 sidecar, --identity the operational age key
#   (D98: outside deploy/); test mode (RESTAURANTPRO_ENVIRONMENT=test): FILE is the plaintext tar.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUPERUSER=restaurantpro
db=restaurantpro
container=""
archive=""
identity=""
other=""

die() { echo "tenant-restore: $*" >&2; exit 1; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"

while (($#)); do
  case "$1" in
    --archive) archive="${2:?--archive needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --identity) identity="${2:?--identity needs a value}"; shift 2 ;;
    --other-installation) other="${2:?--other-installation needs a value}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ -f "$archive" ]] || die "--archive $archive is not a file"
# The name tenant-export.sh gives; it is checked before anything uses it (it is recorded in the audit row).
archive_name="$(basename "$archive")"
[[ "$archive_name" =~ ^tenant-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})-[0-9]{8}T[0-9]{6}Z\.tar(\.age)?$ ]] \
  || die "the archive name is not tenant-<uuid>-<UTC>.tar[.age] as tenant-export.sh writes it"
named_tenant="${BASH_REMATCH[1]}"
[[ -n "$container" ]] || die "--container is required"
require_own_installation "$container" "$other"
env_mode

WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-tenant-restore.XXXXXX")"
remote="/tmp/rp-tenant-restore-$$"
cleanup() {
  docker exec "$container" rm -rf "$remote" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT

if production; then
  [[ -n "$identity" ]] || die "production: --identity (the operational age key) is required"
  require_identity "$identity"
  [[ "$archive" == *.age ]] || die "production: the archive must be the encrypted .tar.age"
  sidecar="${archive%.age}.sha256"
  is_sidecar "$sidecar" || die "no well-formed sidecar $sidecar"
  [[ "$(sha256_hex "$archive")" == "$(sidecar_cipher "$sidecar")" ]] || die "$archive does not match its sidecar"
  age_decrypt "$identity" <"$archive" >"$WORK/tenant.tar" || die "age could not decrypt $archive with $identity"
  [[ "$(sha256_hex "$WORK/tenant.tar")" == "$(sidecar_plain "$sidecar")" ]] || die "the decrypted archive does not match its sidecar"
else
  cp "$archive" "$WORK/tenant.tar"
fi
mkdir "$WORK/stage"
tar -C "$WORK/stage" -xf "$WORK/tenant.tar" || die "$archive is not a tenant export"
manifest="$WORK/stage/MANIFEST"
[[ -f "$manifest" ]] || die "$archive has no MANIFEST"
tenant="$(sed -n 's/^tenant //p' "$manifest")"
[[ "$tenant" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || die "the MANIFEST names no tenant"
[[ "$tenant" == "$named_tenant" ]] || die "the archive name names tenant $named_tenant, its MANIFEST $tenant"

as_super() { docker exec -i "$container" psql -X -q -At -v ON_ERROR_STOP=1 -U "$SUPERUSER" -d "$db" "$@"; }

# The archive is what its MANIFEST says, file by file.
while read -r kind table rows sum; do
  [[ "$kind" == table ]] || continue
  [[ "$table" =~ ^[a-z_][a-z0-9_]*\.[a-z_][a-z0-9_]*$ && "$rows" =~ ^[0-9]+$ ]] || die "the MANIFEST holds a malformed line"
  f="$WORK/stage/$table.copy"
  [[ -f "$f" ]] || die "the MANIFEST lists $table but the archive has no $table.copy"
  [[ "$(wc -l <"$f" | tr -d ' ')" == "$rows" && "$(sha256_hex "$f")" == "$sum" ]] || die "$table.copy does not match the MANIFEST"
done <"$manifest"
expected="$( (as_super -c "SELECT table_name FROM shared.rls_tenant_table"; printf 'tenancy.tenant\ntenancy.tenant_membership\n') | LC_ALL=C sort)"
listed="$(sed -n 's/^table \([^ ]*\) .*/\1/p' "$manifest" | LC_ALL=C sort)"
[[ "$expected" == "$listed" ]] || die "the archive's tables are not the target's TENANT* tables (another release?)"
[[ "$(sed -n 's/^schema_version //p' "$manifest")" == "$(as_super -c "SELECT coalesce(max(version), '') FROM flyway_schema_history WHERE success")" ]] \
  || die "the archive's schema version is not the target's: migrate the staging copy to the same release first"
status="$(as_super -v tenant="$tenant" <<<"SELECT status FROM tenancy.tenant WHERE id = :'tenant'")"
[[ -n "$status" ]] || die "no tenant $tenant on the target"
[[ "$status" == SUSPENDED ]] || die "tenant $tenant is $status: suspend it first (POST /api/v1/platform/tenants/{id}/suspend)"

docker exec "$container" sh -c "umask 077 && mkdir '$remote'"
tar -C "$WORK/stage" -cf - . | docker exec -i "$container" tar -C "$remote" -xf -
operator="ops:$(id -un)"
# Values reach SQL only as psql variables (:'tenant'); table names are the target's own registry entries (checked
# above against it and against an identifier pattern), the only text spliced.
{
  echo "BEGIN;"
  echo "SET LOCAL session_replication_role = replica;"
  echo "SELECT 1 FROM tenancy.tenant WHERE id = :'tenant' AND status = 'SUSPENDED' FOR UPDATE \\g /dev/null"
  while read -r kind table rows sum; do
    [[ "$kind" == table && "$table" != tenancy.tenant ]] || continue
    echo "DELETE FROM $table WHERE tenant_id = :'tenant';"
    echo "\\copy $table FROM '$remote/$table.copy'"
    # a mismatch divides by zero: the transaction is rolled back
    echo "SELECT 1 / (CASE WHEN count(*) = $rows THEN 1 ELSE 0 END) FROM $table WHERE tenant_id = :'tenant' \\g /dev/null"
  done <"$manifest"
  echo "SET LOCAL session_replication_role = origin;"
  echo "INSERT INTO tenancy.platform_audit_event (operator, tenant_id, action, reason)"
  echo "VALUES (:'operator', :'tenant', 'TENANT_RESTORED', 'tenant-restore.sh from ' || :'archive');"
  echo "COMMIT;"
} | as_super -v tenant="$tenant" -v operator="$operator" -v archive="$archive_name" >/dev/null || die "the replacement failed and was rolled back; tenant $tenant is unchanged"
echo "tenant-restore: tenant $tenant restored from $archive_name; it stays SUSPENDED until an operator reactivates it" >&2
