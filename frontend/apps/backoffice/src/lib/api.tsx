import { backOfficeMiddleware, createApiClient, type ApiClient } from '@restaurantpro/api-client';
import type { QueryClient } from '@tanstack/react-query';
import { createContext, useContext, type ReactNode } from 'react';
import { SESSION_QUERY_KEY } from './query-client';

/**
 * The BackOffice client: the BFF session cookie on every call; on unsafe ones the CSRF header and `X-Expected-Tenant`,
 * the company of the session this tab last read, so a write after another tab switched is refused (FC §6, §7, FE-F16).
 * `baseUrl` and `cookies` are for tests.
 */
export function createBackOfficeClient(
  queryClient: QueryClient,
  { baseUrl, cookies }: { baseUrl?: string; cookies?: () => string } = {},
): ApiClient {
  const expectedTenant = () => queryClient.getQueryData<{ tenantId?: string }>(SESSION_QUERY_KEY)?.tenantId;
  return createApiClient({
    ...(baseUrl === undefined ? {} : { baseUrl }),
    middleware: [backOfficeMiddleware({ expectedTenant, ...(cookies === undefined ? {} : { cookies }) })],
  });
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
