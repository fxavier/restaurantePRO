import type { components } from '@restaurantpro/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { deliveriesKey } from './use-deliveries';

export type OpenOrderRequest = components['schemas']['com.xavier.backend.orders.model.dto.OpenOrderRequest'];

/**
 * `POST /api/v1/pos/orders`: a TAKEAWAY or DELIVERY order, one intent per "Abrir takeaway" or "Criar entrega",
 * resolving with the new order's id; its items are added on the order's own screen.
 */
export function useOpenOrder({ onOpened }: { onOpened: (orderId: string) => void }) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  return useIntent(
    async (body: OpenOrderRequest, key: string) =>
      (await api.POST('/api/v1/pos/orders', { params: { header: { 'Idempotency-Key': key } }, body })).data,
    {
      onSuccess(order) {
        // A delivery opens PENDING: the board shows it when the waiter comes back.
        void queryClient.invalidateQueries({ queryKey: deliveriesKey });
        if (order?.id) onOpened(order.id);
      },
    },
  );
}
