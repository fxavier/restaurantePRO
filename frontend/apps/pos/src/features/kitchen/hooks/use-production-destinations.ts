import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

/**
 * `GET /api/v1/pos/production-destinations`: the session store's stations (production destination codes), so the
 * display offers a station before its first round.
 */
export function useProductionDestinations() {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'production-destinations'],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/production-destinations', { signal })).data,
    select: (destinations) => (destinations ?? []).flatMap(({ code }) => (code ? [code] : [])),
  });
}
