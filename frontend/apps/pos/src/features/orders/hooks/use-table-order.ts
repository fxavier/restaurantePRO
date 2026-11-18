import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

/** The backend's largest page (D23): one page holds every open order of a store. */
const PAGE_SIZE = 200;

/** The table's open order; none (`id` null); or not on the page read, which the store's open orders overflowed. */
export type TableOrder = { id: string } | { id: null; truncated: boolean };

/**
 * The open order of a table. The table map does not carry the order's id
 * (gap-table-open-order), so this reads the store's open orders (`GET /api/v1/pos/orders?lifecycle=OPEN`) and picks
 * the table's. Under the `['pos', 'orders']` prefix, so any `ORDER` change reads it again.
 */
export function useTableOrder(tableId: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'orders', 'open', { tableId }],
    queryFn: async ({ signal }) =>
      (
        await api.GET('/api/v1/pos/orders', {
          params: { query: { lifecycle: 'OPEN', pageable: { size: PAGE_SIZE } } },
          // Spring reads the page as plain `size=`, not the `pageable[size]=` a deep object would send.
          querySerializer: { object: { style: 'form', explode: true } },
          signal,
        })
      ).data,
    select: (page): TableOrder => {
      const content = page?.content ?? [];
      const id = content.find((order) => order.tableId === tableId)?.id;
      if (id) return { id };
      return { id: null, truncated: (page?.totalElements ?? 0) > content.length };
    },
  });
}
