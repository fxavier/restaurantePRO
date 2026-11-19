import { backOfficeMiddleware, createApiClient, type ApiClient } from '@restaurantpro/api-client';
import { createContext, useContext, type ReactNode } from 'react';
import type { TenantPin } from './tenant-pin';

/**
 * The BackOffice client: the BFF session cookie on every call; on unsafe ones the CSRF header; once pinned, on every
 * one `X-Expected-Tenant` and `X-Expected-User`, the company and user this tab's data belongs to (the pin), so a write
 * after another tab switched company or logged another user in is refused (FC §6, §7, FE-F16, FE-F22). `baseUrl` and `cookies` are for tests.
 */
export function createBackOfficeClient(
  tenantPin: TenantPin,
  { baseUrl, cookies }: { baseUrl?: string; cookies?: () => string } = {},
): ApiClient {
  const expectedTenant = () => tenantPin.get()?.tenantId;
  const expectedUser = () => tenantPin.get()?.subject;
  return createApiClient({
    ...(baseUrl === undefined ? {} : { baseUrl }),
    middleware: [backOfficeMiddleware({ expectedTenant, expectedUser, ...(cookies === undefined ? {} : { cookies }) })],
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
