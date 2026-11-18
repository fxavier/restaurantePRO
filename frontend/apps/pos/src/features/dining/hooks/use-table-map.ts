import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toAreas } from '../table-map';

/** The query key of the store's table map; a `TABLES` or `ORDER` change invalidates it (FC §10). */
const TABLES_KEY = ['pos', 'tables'] as const;

/**
 * The table map of the session's store (`GET /api/v1/pos/tables`; the store comes from the session, D39). The cache
 * holds the contract's answer; `select` maps it, so an answer the map cannot show is an error, not a crash.
 */
export function useTableMap() {
  const { api } = useServices();
  return useQuery({
    queryKey: TABLES_KEY,
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/tables', { signal })).data,
    select: toAreas,
  });
}
