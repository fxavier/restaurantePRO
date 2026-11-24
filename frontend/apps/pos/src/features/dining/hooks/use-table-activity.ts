import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toActivity } from '../table-activity';

/**
 * The seated orders' totals and last rounds (`GET /api/v1/pos/orders/table-activity`), for the tiles. Under the map's
 * key, so whatever reads the map again (a `TABLES` or `ORDER` change, FC §10) reads this too.
 */
export function useTableActivity() {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'tables', 'activity'],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/orders/table-activity', { signal })).data,
    select: toActivity,
  });
}
