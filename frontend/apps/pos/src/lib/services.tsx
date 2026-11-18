import { createContext, useContext, type ReactNode } from 'react';
import { type ApiClient, createApiClient, posMiddleware } from '@restaurantpro/api-client';
import type { DeviceStore } from './auth/device-store';
import { createPosSession, type PosSession } from './auth/pos-session';
import { endingInvalidSession } from './auth/session-fetch';
import { createTerminalTokens, type TerminalTokens } from './auth/terminal-tokens';
import { withTerminalToken } from './auth/token-fetch';

/** The POS's app-wide services: the typed API client, the terminal token, the device record and the POS session. */
export type PosServices = { api: ApiClient; tokens: TerminalTokens; devices: DeviceStore; session: PosSession };

export function createPosServices({ issuer, devices }: { issuer: string; devices: DeviceStore }): PosServices {
  const tokens = createTerminalTokens({ issuer });
  const session = createPosSession();
  const api = createApiClient({
    // Same origin as the backend (ADR 0041); absolute, because a Request needs an absolute URL outside a page too.
    baseUrl: window.location.origin,
    fetch: endingInvalidSession(session, withTerminalToken(tokens)),
    middleware: [posMiddleware({ accessToken: tokens.accessToken, posSession: session.token })],
  });
  return { api, tokens, devices, session };
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
