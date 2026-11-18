import { backOfficeMiddleware, createApiClient, type ApiClient } from '@restaurantpro/api-client';
import { createContext, useContext, type ReactNode } from 'react';

/** The BackOffice client: the BFF session cookie on every call, the CSRF header on unsafe ones (FC §6, §7). */
export function createBackOfficeClient(): ApiClient {
  return createApiClient({ middleware: [backOfficeMiddleware()] });
}

const ApiContext = createContext<ApiClient | null>(null);

export function ApiProvider({ client, children }: { client: ApiClient; children: ReactNode }) {
  return <ApiContext value={client}>{children}</ApiContext>;
}

/** Feature hooks reach the API only through this (FC §7); components never call it. */
export function useApi(): ApiClient {
  const client = useContext(ApiContext);
  if (!client) throw new Error('useApi needs an ApiProvider');
  return client;
}
