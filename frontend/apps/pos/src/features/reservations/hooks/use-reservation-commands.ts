import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { RESERVATIONS_KEY } from './use-reservations';

const TABLES_KEY = ['pos', 'tables'] as const;

/** Whatever the answer, the day and the table map are read again: the server's states are the ones shown. */
function useRefresh() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: RESERVATIONS_KEY });
    void queryClient.invalidateQueries({ queryKey: TABLES_KEY });
  };
}

export type SeatRequest = { tableId: string; guests: number; reservationId: string };

/**
 * Seating (D126): `POST /api/v1/pos/tables/{tableId}/open` with the reservation, which moves it BOOKED → SEATED in the
 * same transaction. One intent per "Sentar"; resolves with the new order's id.
 */
export function useSeat({ onSeated }: { onSeated: (orderId: string) => void }) {
  const { api } = useServices();
  const refresh = useRefresh();
  return useIntent(
    async ({ tableId, guests, reservationId }: SeatRequest, key: string) =>
      (
        await api.POST('/api/v1/pos/tables/{tableId}/open', {
          params: { path: { tableId }, header: { 'Idempotency-Key': key } },
          body: { guests, reservationId },
        })
      ).data,
    {
      onSuccess(order) {
        refresh();
        if (order?.id) onSeated(order.id);
      },
      onError: refresh,
      onDiscard: refresh,
    },
  );
}
