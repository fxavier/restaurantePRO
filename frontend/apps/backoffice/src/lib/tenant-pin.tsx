import { createContext, useContext, type ReactNode } from 'react';

/**
 * The company this tab's loaded data belongs to (FE-F16), in memory and per tab. Every write names it as
 * `X-Expected-Tenant`. It changes only through this tab's own switch or once a detected change has reset the tab, so
 * a write from a page still showing the previous company is refused by the BFF (409 `TENANT_CHANGED`).
 */
export type TenantPin = { get(): string | undefined; set(tenantId: string): void };

export function createTenantPin(): TenantPin {
  let pinned: string | undefined;
  return {
    get: () => pinned,
    set: (tenantId) => {
      pinned = tenantId;
    },
  };
}

const TenantPinContext = createContext<TenantPin | null>(null);

export function TenantPinProvider({ pin, children }: { pin: TenantPin; children: ReactNode }) {
  return <TenantPinContext value={pin}>{children}</TenantPinContext>;
}

export function useTenantPin(): TenantPin {
  const pin = useContext(TenantPinContext);
  if (!pin) throw new Error('useTenantPin needs a TenantPinProvider');
  return pin;
}
