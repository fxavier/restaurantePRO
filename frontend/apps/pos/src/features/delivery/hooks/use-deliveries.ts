import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toDeliveries } from '../delivery';

/** The board's query key; every transition reads it again. */
export const deliveriesKey = ['pos', 'deliveries'] as const;

/**
 * The store's active deliveries (`GET /api/v1/pos/deliveries`). A delivery's change notifies `ORDER` with its order id,
 * which this key does not follow (lib/live), so the board also reads itself again every 15 s, as the change stream's
 * fallback does.
 */
export function useDeliveries() {
  const { api } = useServices();
  return useQuery({
    queryKey: deliveriesKey,
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/deliveries', { signal })).data,
    select: toDeliveries,
    refetchInterval: 15_000,
  });
}
