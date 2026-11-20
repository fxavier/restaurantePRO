import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { settlementKey } from './use-settlement';

/**
 * A settlement command: one intent per press (FC §8), and the bill read again whatever the answer, so the screen shows
 * the server's parts, payments and states, never a guess.
 */
function useSettlementIntent<Request, Result>(orderId: string, send: (request: Request, key: string) => Promise<Result>) {
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: settlementKey(orderId) });
  return useIntent<Request, Result>(send, { onSuccess: refresh, onError: refresh, onDiscard: refresh });
}

/** `POST /api/v1/pos/orders/{id}/partitions` with kind `ITEMS`: quantities of lines as one part (D67). */
export function useSplitByItems(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async (body: { orderVersion: number; lines: { lineId: string; quantity: string }[] }, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/partitions', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body: { orderVersion: body.orderVersion, kind: 'ITEMS', lines: body.lines },
        })
      ).data,
  );
}

/** `POST /api/v1/pos/orders/{id}/partitions/equal`: the residual in `parts` equal parts, at the bill's version. */
export function useSplitEqually(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async (body: { orderVersion: number; parts: number }, key) =>
    (
      await api.POST('/api/v1/pos/orders/{id}/partitions/equal', {
        params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
        body,
      })
    ).data,
  );
}
