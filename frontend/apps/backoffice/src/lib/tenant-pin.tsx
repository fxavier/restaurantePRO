import { createContext, useContext, type ReactNode } from 'react';

/**
 * The session and company this page was loaded in (FE-F16), in memory and per tab: the company by the first successful
 * session read, the user by the first answer that names one, and neither ever moved; only a page load starts a new one.
 * Every call names the company as `X-Expected-Tenant` and the user as `X-Expected-User` (FE-F22). The user is the
 * Keycloak subject the BFF names in `X-Relayed-User`, the only user id it knows (`userId` is the backend's account id).
 * It is pinned from any answer, an error one included, so a user who must still choose a company is pinned before the
 * choice. An answer from another company or session reloads the page instead of being shown.
 */
export type Pinned = { tenantId: string; userId: string | undefined };
export type TenantPin = {
  get(): Pinned | undefined;
  setOnce(pinned: Pinned): void;
  user(): string | undefined;
  setUserOnce(subject: string): void;
};

export function createTenantPin(): TenantPin {
  let pinned: Pinned | undefined;
  let subject: string | undefined;
  return {
    get: () => pinned,
    setOnce: (first) => {
      pinned ??= first;
    },
    user: () => subject,
    setUserOnce: (first) => {
      subject ??= first;
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
