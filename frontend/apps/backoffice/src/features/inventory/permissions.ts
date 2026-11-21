/**
 * Whether a permission is held for the store: the backend's authorities are `PERM_<NAME>` company-wide or
 * `PERM_<NAME>@<storeId>` in one store. Showing a control is a convenience only; the server decides (§10).
 */
export function holds(permissions: readonly string[], name: string, storeId: string): boolean {
  const authority = `PERM_${name}`;
  return permissions.includes(authority) || permissions.includes(`${authority}@${storeId}`);
}
