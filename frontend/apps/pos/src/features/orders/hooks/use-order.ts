import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toOrder } from '../order';

/** The query key of one order; an `ORDER` change with its id invalidates it (FC §10). */
export const orderKey = (orderId: string) => ['pos', 'orders', orderId] as const;

/** One order as the server has it (`GET /api/v1/pos/orders/{id}`); never changed here, only read again. */
export function useOrder(orderId: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: orderKey(orderId),
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/orders/{id}', { params: { path: { id: orderId } }, signal })).data,
    select: toOrder,
  });
}
