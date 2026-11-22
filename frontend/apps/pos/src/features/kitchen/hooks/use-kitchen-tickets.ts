import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toKitchenTickets, type KitchenStatus } from '../kitchen';

/** Every kitchen display query starts here; a `PRODUCTION_TICKETS` notice and every command read it again (lib/live). */
export const kitchenKey = ['pos', 'production-tickets'] as const;

/** How many rounds taken off the screen the history shows. */
const HISTORY_SIZE = 10;

export type KitchenFilter = {
  /** The station (a production destination code); none for every station. */
  destination?: string;
  statuses: readonly KitchenStatus[];
  /** History: the latest rounds first, only the last few. The board reads the oldest first (the backend's default). */
  latestFirst?: boolean;
};

/** `GET /api/v1/pos/production-tickets/kds`: the session store's rounds by station and state. */
export function useKitchenTickets({ destination, statuses, latestFirst = false }: KitchenFilter) {
  const { api } = useServices();
  const order = latestFirst ? { sort: ['queuedAt,DESC'], size: HISTORY_SIZE } : {};
  return useQuery({
    queryKey: [...kitchenKey, 'kds', { destination, statuses, latestFirst }],
    queryFn: async ({ signal }) =>
      (
        await api.GET('/api/v1/pos/production-tickets/kds', {
          params: { query: { destination, status: [...statuses], ...order } },
          signal,
        })
      ).data,
    select: (page) => toKitchenTickets(page?.content),
  });
}
