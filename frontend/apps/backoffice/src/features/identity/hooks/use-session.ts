import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { SessionView, TenantChoice } from '../types';

/** The signed-in user: name and permissions in the selected company (FC §6). */
export function useSession() {
  const api = useApi();
  return useQuery({
    queryKey: ['auth', 'session'],
    queryFn: async (): Promise<SessionView> => (await api.GET('/api/v1/auth/session')).data ?? {},
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
