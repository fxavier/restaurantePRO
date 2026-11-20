import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { toSettlement } from '../settlement';

/**
 * Under the order's key, so an `ORDER` change with its id (partitions, payments, documents, D95) reads it again
 * (FC §10); never changed here.
 */
export const settlementKey = (orderId: string) => ['pos', 'orders', orderId, 'settlement'] as const;

/** One order's bill as settlement has it (`GET /api/v1/pos/orders/{id}/settlement`). */
export function useSettlement(orderId: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: settlementKey(orderId),
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/orders/{id}/settlement', { params: { path: { id: orderId } }, signal })).data,
    select: toSettlement,
  });
}

/**
 * The order's table and number, for the screen's title: the same query and key as the order screen's, so both share
 * one read (`GET /api/v1/pos/orders/{id}`).
 */
export function useOrderHeading(orderId: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'orders', orderId],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/orders/{id}', { params: { path: { id: orderId } }, signal })).data,
    select: (order) => ({ tableCode: order?.tableCode ?? null, orderNumber: order?.orderNumber, guests: order?.guests ?? null }),
  });
}
