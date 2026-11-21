import { useQueryClient } from '@tanstack/react-query';
import type { ApiClient } from '@restaurantpro/api-client';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { isChangedElsewhere, type Transition } from '../delivery';
import { deliveriesKey } from './use-deliveries';

/**
 * `POST /api/v1/pos/deliveries/{orderId}/dispatch|delivered|returned`: one intent per tap, with its own key (FC §8).
 * The board is read again after any answer: the server's state is the one shown, never this command's guess.
 */
export function useDeliveryTransition(
  orderId: string,
  { onDone, onChangedElsewhere }: { onDone?: () => void; onChangedElsewhere?: () => void } = {},
) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: deliveriesKey });
  return useIntent<Transition, unknown>((transition, key) => send(api, orderId, transition, key), {
    onSuccess() {
      refresh();
      onDone?.();
    },
    onError(error) {
      refresh();
      if (isChangedElsewhere(error)) onChangedElsewhere?.();
    },
    onDiscard: refresh,
  });
}

async function send(api: ApiClient, orderId: string, transition: Transition, key: string) {
  const params = { path: { orderId }, header: { 'Idempotency-Key': key } };
  switch (transition.kind) {
    case 'dispatch': {
      const { courierEmployeeId, version } = transition;
      return (await api.POST('/api/v1/pos/deliveries/{orderId}/dispatch', { params, body: { courierEmployeeId, version } })).data;
    }
    case 'delivered':
      return (await api.POST('/api/v1/pos/deliveries/{orderId}/delivered', { params, body: { version: transition.version } }))
        .data;
    case 'returned': {
      const { reason, version } = transition;
      return (await api.POST('/api/v1/pos/deliveries/{orderId}/returned', { params, body: { reason, version } })).data;
    }
  }
}
