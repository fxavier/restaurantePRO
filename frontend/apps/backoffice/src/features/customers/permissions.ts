/** The backend permissions this screen's controls depend on (backend `access.Permission`). */
export type CustomerPermission =
  | 'CUSTOMER_MANAGE'
  | 'CREDIT_LIMIT_MANAGE'
  | 'LOYALTY_VIEW'
  | 'LOYALTY_ENROLL'
  | 'LOYALTY_ADJUST';

/** What the user may do here, read once from the session's authorities. */
export type Allowed = Readonly<Record<CustomerPermission, boolean>>;

const ALL: readonly CustomerPermission[] = [
  'CUSTOMER_MANAGE',
  'CREDIT_LIMIT_MANAGE',
  'LOYALTY_VIEW',
  'LOYALTY_ENROLL',
  'LOYALTY_ADJUST',
];

/**
 * `SessionView.permissions` are `PERM_<NAME>` company-wide or `PERM_<NAME>@<storeId>` in one store. A control shows
 * with the permission anywhere: hiding is a convenience, the backend decides (§10).
 */
export function allowedFrom(authorities: readonly string[]): Allowed {
  const granted = (name: CustomerPermission) =>
    authorities.some((authority) => authority === `PERM_${name}` || authority.startsWith(`PERM_${name}@`));
  return Object.fromEntries(ALL.map((name) => [name, granted(name)])) as Record<CustomerPermission, boolean>;
}
