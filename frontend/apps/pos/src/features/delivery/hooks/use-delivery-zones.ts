import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

/** `GET /api/v1/pos/delivery-zones`: the store's active zones, each carrying its fee (added by the server). */
export function useDeliveryZones() {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'delivery-zones'],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/delivery-zones', { signal })).data ?? [],
  });
}
