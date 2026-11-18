import { ApiError } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { loginSucceeded } from '../../../lib/browser';
import { useTenantPin } from '../../../lib/tenant-pin';
import type { SessionView, TenantChoice } from '../types';

/**
 * The signed-in user: name, permissions and the company the server resolved the request in (FC §6, FE-F08).
 * The BFF's company selection is shared by every tab, so another tab may switch it. The session is therefore never
 * fresh and is reread whenever this tab regains focus, so the label never shows a company the requests no longer act
 * in. The first read pins the tab to its session and company. A later read naming another company or user, even one
 * the BFF did not stamp with X-Relayed-Tenant, is never shown: it reloads the page (FE-F16, `lib/tenant-pin`).
 */
export function useSession() {
  const api = useApi();
  const pin = useTenantPin();
  return useQuery({
    queryKey: ['auth', 'session'],
    staleTime: 0,
    refetchOnWindowFocus: 'always',
    queryFn: async (): Promise<SessionView> => {
      const { data } = await api.GET('/api/v1/auth/session');
      loginSucceeded();
      const pinned = pin.get();
      if (pinned && (data?.tenantId !== pinned.tenantId || data?.userId !== pinned.userId)) {
        throw new ApiError({ status: 409, code: 'TENANT_CHANGED', title: 'The session or tenant changed in another tab' });
      }
      if (data?.tenantId) pin.setOnce({ tenantId: data.tenantId, userId: data.userId });
      return data ?? {};
    },
  });
}

/** The user's memberships; needs no company selected (TenantResolutionFilter). */
export function useTenants() {
  const api = useApi();
  return useQuery({
    queryKey: ['auth', 'tenants'],
    queryFn: async (): Promise<TenantChoice[]> => (await api.GET('/api/v1/auth/tenants')).data ?? [],
  });
}
