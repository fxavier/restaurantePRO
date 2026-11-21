import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toReservations } from '../reservation';

/** Every day's list starts with this key, so a change invalidates them all. */
export const RESERVATIONS_KEY = ['pos', 'reservations'] as const;

/** `GET /api/v1/pos/reservations?date=`: the session store's reservations of a day, every state, by start. */
export function useReservations(date: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: [...RESERVATIONS_KEY, date],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/reservations', { params: { query: { date } }, signal })).data,
    select: toReservations,
  });
}
