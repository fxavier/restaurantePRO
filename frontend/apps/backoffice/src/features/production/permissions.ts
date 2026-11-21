/**
 * Whether the session's authorities (`PERM_<NAME>` company-wide, `PERM_<NAME>@<storeId>` in one store) grant `name`
 * in this store. Only hides controls; the backend decides (§10).
 */
export function holdsIn(permissions: readonly string[], name: string, storeId: string): boolean {
  const authority = `PERM_${name}`;
  return permissions.includes(authority) || permissions.includes(`${authority}@${storeId}`);
}
