import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { loginSucceeded } from '../../../lib/browser';
import type { SessionView, TenantChoice } from '../types';

/** The signed-in user: name and permissions in the selected company (FC §6). */
export function useSession() {
  const api = useApi();
  return useQuery({
    queryKey: ['auth', 'session'],
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
