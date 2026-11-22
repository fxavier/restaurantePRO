import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { OrderLifecycle, OrderMode, OrderRow, OrderView } from '../types';

export const ORDERS_PAGE_SIZE = 50;

/** The search's filters; each one absent is not sent, so the server applies no bound (business days, newest first). */
export type OrderFilter = { from?: string; to?: string; status?: OrderLifecycle; mode?: OrderMode; q?: string };
export type OrdersPage = { orders: OrderRow[]; totalElements: number };

/** One page of the store's orders matching the filter. */
export function useOrders(storeId: string, filter: OrderFilter, page: number, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'orders', storeId, filter, page],
    queryFn: async (): Promise<OrdersPage> => {
      const { data } = await api.GET('/api/v1/backoffice/orders', {
        params: { query: { storeId, ...filter, page, size: ORDERS_PAGE_SIZE } },
      });
      return { orders: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** One order with its lines, rounds and adjustments, as the server holds it now. */
export function useOrder(orderId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'orders', 'detail', orderId],
    queryFn: async (): Promise<OrderView | undefined> => {
      const { data } = await api.GET('/api/v1/backoffice/orders/{id}', { params: { path: { id: orderId } } });
      return data;
    },
  });
}
