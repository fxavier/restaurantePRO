import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

/** `GET /api/v1/pos/couriers`: the store's active employees who may take a delivery out. */
export function useCouriers() {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'couriers'],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/couriers', { signal })).data ?? [],
  });
}
