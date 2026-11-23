import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toKitchenPage, type KitchenStatus } from '../kitchen';

/** Every kitchen display query starts here; a `PRODUCTION_TICKETS` notice and every command read it again (lib/live). */
export const kitchenKey = ['pos', 'production-tickets'] as const;

/**
 * The board: the largest page the server serves (`max-page-size`, D23), oldest first (the backend's default), so a
 * store at peak loses no round to the backend's default of 50; beyond it the screen says how many it does not show.
 */
const BOARD = { size: 200 };

/** History: the latest rounds taken off (ADR 0044, recall), by the time they were bumped, only the last few. */
const HISTORY = { sort: ['bumped.at,DESC'], size: 10 };

export type KitchenFilter = {
  /** The station (a production destination code); none for every station. */
  destination?: string;
  statuses: readonly KitchenStatus[];
  /** The latest rounds taken off instead of the board's oldest first. */
  latestBumped?: boolean;
};

/** `GET /api/v1/pos/production-tickets/kds`: the session store's rounds by station and state. */
export function useKitchenTickets({ destination, statuses, latestBumped = false }: KitchenFilter) {
  const { api } = useServices();
  const order = latestBumped ? HISTORY : BOARD;
  return useQuery({
    queryKey: [...kitchenKey, 'kds', { destination, statuses, latestBumped }],
    queryFn: async ({ signal }) =>
      (
        await api.GET('/api/v1/pos/production-tickets/kds', {
          params: { query: { destination, status: [...statuses], ...order } },
          signal,
        })
      ).data,
    select: toKitchenPage,
  });
}
