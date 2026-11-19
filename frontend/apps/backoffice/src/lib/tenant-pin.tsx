import { createContext, useContext, type ReactNode } from 'react';

/**
 * The session and company this page was loaded in (FE-F16), in memory and per tab: set by the first successful
 * session read and never moved; only a page load starts a new one. Every call names the company as
 * `X-Expected-Tenant` and the user as `X-Expected-User` (FE-F22: the Keycloak subject the BFF names in
 * `X-Relayed-User`, the only user id it knows; `userId` is the backend's account id, which the BFF does not know); an
 * answer from another company or session reloads the page instead of being shown.
 */
export type Pinned = { tenantId: string; userId: string | undefined; subject: string | undefined };
export type TenantPin = { get(): Pinned | undefined; setOnce(pinned: Pinned): void };

export function createTenantPin(): TenantPin {
  let pinned: Pinned | undefined;
  return {
    get: () => pinned,
    setOnce: (first) => {
      pinned ??= first;
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
