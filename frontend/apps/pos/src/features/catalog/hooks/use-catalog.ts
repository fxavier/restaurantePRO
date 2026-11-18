import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toCatalog } from '../catalog';

/** The query key of the published catalog; a `CATALOG` change invalidates it (FC §10). */
const CATALOG_KEY = ['pos', 'catalog'] as const;

/** The session store's published catalog (`GET /api/v1/pos/catalog`; the store comes from the session, D39). */
export function useCatalog() {
  const { api } = useServices();
  return useQuery({
    queryKey: CATALOG_KEY,
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/catalog', { signal })).data,
    select: toCatalog,
  });
}
