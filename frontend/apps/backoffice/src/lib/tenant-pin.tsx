import { createContext, useContext, type ReactNode } from 'react';

/**
 * The company this page was loaded in (FE-F16), in memory and per tab: set by the first successful session read and
 * never moved; only a page load starts a new one. Writes name it as `X-Expected-Tenant`, and an answer the BFF relayed
 * in another company reloads the page instead of being shown.
 */
export type TenantPin = { get(): string | undefined; setOnce(tenantId: string): void };

export function createTenantPin(): TenantPin {
  let pinned: string | undefined;
  return {
    get: () => pinned,
    setOnce: (tenantId) => {
      pinned ??= tenantId;
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
