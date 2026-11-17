#!/usr/bin/env bash
# Per-tenant export (D188; contract end, portability, the tenant-restore procedure). Exports every TENANT* table
# (shared.rls_tenant_table: each has tenant_id) filtered by the tenant, plus the tenant's tenancy.tenant row and its
# memberships, in ONE read-only REPEATABLE READ transaction (one consistent snapshot), as restaurantpro_owner over the
# container's local socket. The tenant is bound in that transaction (set_config(..., true), all stores): with the
# owner's BYPASSRLS (D188 (a)) the filter does the work, and under fallback (b) (no BYPASSRLS, FORCE ROW LEVEL
# SECURITY) the binding is what lets the owner read the rows; both give the same archive.
#
# The archive is a tar of one COPY file per table and a MANIFEST (tenant, UTC time, schema version, and per table
# its row count and SHA-256). Production (D98): encrypted with age to the backup recipients (at least two: the
# operational and the recovery key), with its .sha256 sidecar; no plaintext is left behind. Test mode
# (RESTAURANTPRO_ENVIRONMENT=test): plaintext tar.
#
# Usage: deploy/tenant-export.sh --tenant UUID --container NAME [--db NAME] [--out DIR] [--other-installation PROJECT]
# Prints the archive path. Exits non-zero, writing nothing, on any failure.
set -euo pipefail
umask 077

DEPLOY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OWNER=restaurantpro_owner
db=restaurantpro
container=""
tenant=""
out="."
other=""

die() { echo "tenant-export: $*" >&2; exit 1; }
# shellcheck source=lib/common.sh
. "$DEPLOY_DIR/lib/common.sh"
# shellcheck source=lib/age.sh
. "$DEPLOY_DIR/lib/age.sh"

while (($#)); do
  case "$1" in
    --tenant) tenant="${2:?--tenant needs a value}"; shift 2 ;;
    --container) container="${2:?--container needs a value}"; shift 2 ;;
    --db) db="${2:?--db needs a value}"; shift 2 ;;
    --out) out="${2:?--out needs a value}"; shift 2 ;;
    --other-installation) other="${2:?--other-installation needs a value}"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done
[[ "$tenant" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || die "--tenant must be a lower-case UUID"
[[ -n "$container" ]] || die "--container is required"
[[ -d "$out" ]] || die "--out $out is not a directory"
require_own_installation "$container" "$other"
env_mode
recipients=""
if production; then
  recipients="$(recipients_file)"
  require_safe_recipients "$recipients" 2
fi

as_owner() { docker exec -i "$container" psql -X -q -At -v ON_ERROR_STOP=1 -U "$OWNER" -d "$db" "$@"; }

[[ "$(as_owner -c "SELECT count(*) FROM tenancy.tenant WHERE id = '$tenant'")" == 1 ]] || die "no tenant $tenant"
tables=()
while IFS= read -r t; do tables+=("$t"); done < <(as_owner -c "SELECT table_name FROM shared.rls_tenant_table ORDER BY table_name")
((${#tables[@]})) || die "shared.rls_tenant_table lists no table"

remote="/tmp/rp-tenant-export-$tenant-$$"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/rp-tenant-export.XXXXXX")"
cleanup() {
  docker exec "$container" rm -rf "$remote" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  remove_partials
}
trap cleanup EXIT
docker exec "$container" sh -c "umask 077 && mkdir '$remote'"

# One transaction, one snapshot; the tenant bound as a request of that tenant would be (D178).
{
  echo "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;"
  echo "SELECT set_config('app.tenant_id', '$tenant', true), set_config('app.all_stores', 'on', true),"
  echo "       set_config('app.store_ids', '{}', true), set_config('app.scope_origin', 'tenant-export', true) \\g /dev/null"
  for t in "${tables[@]}"; do
    echo "\\copy (SELECT * FROM $t WHERE tenant_id = '$tenant' ORDER BY 1) TO '$remote/$t.copy'"
  done
  echo "\\copy (SELECT * FROM tenancy.tenant WHERE id = '$tenant') TO '$remote/tenancy.tenant.copy'"
  echo "\\copy (SELECT * FROM tenancy.tenant_membership WHERE tenant_id = '$tenant' ORDER BY id) TO '$remote/tenancy.tenant_membership.copy'"
  echo "\\copy (SELECT coalesce(max(version), '') FROM flyway_schema_history WHERE success) TO '$remote/SCHEMA_VERSION'"
  echo "COMMIT;"
} | as_owner >/dev/null || die "the export transaction failed (nothing written)"

mkdir "$WORK/stage"
docker exec "$container" tar -C "$remote" -cf - . | tar -C "$WORK/stage" -xf -
{
  echo "tenant $tenant"
  echo "exported_at $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "schema_version $(cat "$WORK/stage/SCHEMA_VERSION")"
  for f in "$WORK/stage"/*.copy; do
    echo "table $(basename "$f" .copy) $(wc -l <"$f" | tr -d ' ') $(sha256_hex "$f")"
  done
} >"$WORK/stage/MANIFEST"
rm -f "$WORK/stage/SCHEMA_VERSION"

name="tenant-$tenant-$(date -u +%Y%m%dT%H%M%SZ).tar"
(cd "$WORK/stage" && LC_ALL=C tar -cf "$WORK/$name" MANIFEST ./*.copy)
seal "$WORK/$name" "$out/$name" "$recipients"
if production; then echo "$out/$name.age"; else echo "$out/$name"; fi
