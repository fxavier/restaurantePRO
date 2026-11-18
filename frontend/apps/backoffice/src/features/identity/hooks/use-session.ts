import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { loginSucceeded } from '../../../lib/browser';
import type { SessionView, TenantChoice } from '../types';

/**
 * The signed-in user: name, permissions and the company the server resolved the request in (FC §6, FE-F08).
 * The BFF's company selection is shared by every tab, so another tab may switch it. The session is therefore never
 * fresh and is reread whenever this tab regains focus, so the label never shows a company the requests no longer act
 * in. The full guard (X-Expected-Tenant, 409 TENANT_CHANGED) is FE-F16.
 */
export function useSession() {
  const api = useApi();
  return useQuery({
    queryKey: ['auth', 'session'],
    staleTime: 0,
    refetchOnWindowFocus: 'always',
    queryFn: async (): Promise<SessionView> => {
      const { data } = await api.GET('/api/v1/auth/session');
      loginSucceeded();
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
