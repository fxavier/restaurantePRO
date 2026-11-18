import { createContext, useContext, type ReactNode } from 'react';
import { type ApiClient, createApiClient, posMiddleware } from '@restaurantpro/api-client';
import type { DeviceStore } from './auth/device-store';
import { createTerminalTokens, type TerminalTokens } from './auth/terminal-tokens';
import { withTerminalToken } from './auth/token-fetch';

/** The POS's app-wide services: the typed API client, the terminal token and the device record. */
export type PosServices = { api: ApiClient; tokens: TerminalTokens; devices: DeviceStore };

export function createPosServices({ issuer, devices }: { issuer: string; devices: DeviceStore }): PosServices {
  const tokens = createTerminalTokens({ issuer });
  const api = createApiClient({
    // Same origin as the backend (ADR 0041); absolute, because a Request needs an absolute URL outside a page too.
    baseUrl: window.location.origin,
    fetch: withTerminalToken(tokens),
    // The POS session arrives with the PIN login (issue 09).
    middleware: [posMiddleware({ accessToken: tokens.accessToken, posSession: () => undefined })],
  });
  return { api, tokens, devices };
}

const ServicesContext = createContext<PosServices | null>(null);

export function ServicesProvider({ services, children }: { services: PosServices; children: ReactNode }) {
  return <ServicesContext value={services}>{children}</ServicesContext>;
}

export function useServices(): PosServices {
  const services = useContext(ServicesContext);
  if (!services) throw new Error('useServices outside ServicesProvider');
  return services;
}
