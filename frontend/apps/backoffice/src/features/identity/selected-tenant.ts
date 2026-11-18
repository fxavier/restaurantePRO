import type { TenantChoice } from './types';

/**
 * The BFF keeps the selected company in its session but no endpoint tells which one it is (contract gap: SessionView
 * has no tenant id). The BackOffice remembers its own last choice to label the switcher. It is an id, not a secret,
 * and is shared by the tabs of one browser, like the BFF session cookie.
 */
const KEY = 'restaurantpro.backoffice.tenantId';

export function rememberTenant(tenantId: string) {
  localStorage.setItem(KEY, tenantId);
}

export function forgetTenant() {
  localStorage.removeItem(KEY);
}

/** With one membership the backend selects it; with several, the remembered choice if it is still a membership. */
export function currentTenant(tenants: TenantChoice[]): TenantChoice | undefined {
  if (tenants.length === 1) return tenants[0];
  const id = localStorage.getItem(KEY);
  return tenants.find((tenant) => tenant.tenantId === id);
}
