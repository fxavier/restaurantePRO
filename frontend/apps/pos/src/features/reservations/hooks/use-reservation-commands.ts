import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { RESERVATIONS_KEY } from './use-reservations';

const TABLES_KEY = ['pos', 'tables'] as const;

/**
 * A reservation command whose contract requires an Idempotency-Key: one intent per tap (FC §8). Whatever the answer,
 * the day and the table map are read again, so the states shown are the server's; `onDone` follows a success.
 */
function useReservationIntent<Request, Result>(
  send: (request: Request, key: string) => Promise<Result>,
  onDone: (result: Result) => void,
) {
  const queryClient = useQueryClient();
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: RESERVATIONS_KEY });
    void queryClient.invalidateQueries({ queryKey: TABLES_KEY });
  };
  return useIntent(send, {
    onSuccess(result) {
      refresh();
      onDone(result);
    },
    onError: refresh,
    onDiscard: refresh,
  });
}

/**
 * `POST /api/v1/pos/reservations/{id}/cancel` with the reason and the version read (D17). A version conflict is
 * definitive, so the next tap is a new intent, with the version read again.
 */
export function useCancelReservation(reservationId: string, { onDone }: { onDone: () => void }) {
  const { api } = useServices();
  return useReservationIntent(
    (body: { reason: string; version: number }, key: string) =>
      api.POST('/api/v1/pos/reservations/{id}/cancel', {
        params: { path: { id: reservationId }, header: { 'Idempotency-Key': key } },
        body,
      }),
    onDone,
  );
}

/** `POST /api/v1/pos/reservations/{id}/no-show` with the version read; the server refuses one before its start (P47). */
export function useMarkNoShow(reservationId: string, { onDone }: { onDone: () => void }) {
  const { api } = useServices();
  return useReservationIntent(
    (body: { version: number }, key: string) =>
      api.POST('/api/v1/pos/reservations/{id}/no-show', {
        params: { path: { id: reservationId }, header: { 'Idempotency-Key': key } },
        body,
      }),
    onDone,
  );
}

export type SeatRequest = { tableId: string; guests: number; reservationId: string };

/**
 * Seating (D126): `POST /api/v1/pos/tables/{tableId}/open` with the reservation, which moves it BOOKED → SEATED in the
 * same transaction. One intent per "Sentar"; `onSeated` gets the new order's id.
 */
export function useSeat({ onSeated }: { onSeated: (orderId: string) => void }) {
  const { api } = useServices();
  return useReservationIntent(
    async ({ tableId, guests, reservationId }: SeatRequest, key: string) =>
      (
        await api.POST('/api/v1/pos/tables/{tableId}/open', {
          params: { path: { tableId }, header: { 'Idempotency-Key': key } },
          body: { guests, reservationId },
        })
      ).data,
    (order) => {
      if (order?.id) onSeated(order.id);
    },
  );
}
