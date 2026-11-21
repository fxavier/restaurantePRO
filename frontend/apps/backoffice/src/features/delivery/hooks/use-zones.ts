import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { ZoneView } from '../types';

export const ZONES_KEY = ['backoffice', 'delivery-zones'] as const;

/** A store's delivery zones, active or not, in code order (the server's order; a store has a handful). */
export function useZones(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...ZONES_KEY, storeId],
    queryFn: async (): Promise<ZoneView[]> => {
      const { data } = await api.GET('/api/v1/backoffice/delivery-zones', { params: { query: { storeId } } });
      return data ?? [];
    },
  });
}
