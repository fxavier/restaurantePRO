import type { components } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { orderKey } from './use-order';

type Schemas = components['schemas'];
export type AddLineRequest = Schemas['com.xavier.backend.orders.model.dto.AddLineRequest'];
type UpdateLineRequest = Schemas['com.xavier.backend.orders.model.dto.UpdateLineRequest'];

/** `POST /api/v1/pos/tables/{tableId}/open`: one intent per "Abrir mesa", resolving with the new order's id. */
export function useOpenTable(tableId: string, onOpened: (orderId: string) => void) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  return useIntent(
    async (guests: number, key: string) =>
      (
        await api.POST('/api/v1/pos/tables/{tableId}/open', {
          params: { path: { tableId }, header: { 'Idempotency-Key': key } },
          body: { guests },
        })
      ).data,
    {
      onSuccess(order) {
        void queryClient.invalidateQueries({ queryKey: ['pos', 'tables'] });
        if (order?.id) onOpened(order.id);
      },
      // Whatever refused it (locked, just taken), the map is read again to show why.
      onError: () => void queryClient.invalidateQueries({ queryKey: ['pos', 'tables'] }),
    },
  );
}

/**
 * `POST /api/v1/pos/orders/{id}/lines`: each tap is one intent with its own key (FC §8); `label` names it on screen.
 * The order is read again afterwards, whatever the answer: the server's lines and prices are the ones shown.
 */
export function useAddLine(orderId: string) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: orderKey(orderId) });
  return useIntent<AddLineRequest, unknown, { label: string }>(
    async (body, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/lines', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body,
        })
      ).data,
    { onSuccess: refresh, onError: refresh },
  );
}

/**
 * A draft line's change (`PUT`, a full replacement of variant, quantity and notes) or removal (`DELETE`), each with
 * the line's version (D17). The contract has no Idempotency-Key on either: the version guards a repeat. The order is
 * read again after either answer, so a version conflict shows the current lines.
 */
export function useDraftLineCommands(orderId: string) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const onSettled = () => queryClient.invalidateQueries({ queryKey: orderKey(orderId) });
  const update = useMutation({
    mutationFn: async ({ lineId, body }: { lineId: string; body: UpdateLineRequest }) =>
      (await api.PUT('/api/v1/pos/orders/{id}/lines/{lineId}', { params: { path: { id: orderId, lineId } }, body })).data,
    onSettled,
  });
  const remove = useMutation({
    mutationFn: async ({ lineId, version }: { lineId: string; version: number }) => {
      await api.DELETE('/api/v1/pos/orders/{id}/lines/{lineId}', {
        params: { path: { id: orderId, lineId } },
        body: { version },
      });
    },
    onSettled,
  });
  return { update, remove };
}
