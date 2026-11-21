import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { ItemSummary, ItemVariantSummary } from '../types';

/** One page large enough for a catalog's items; the picker is not a table (simplification, no paging UI). */
const ITEM_PAGE = { page: 0, size: 200 };

/**
 * The catalog items a zone's fee can be: kind FEE only (D128). The contract has no kind filter, so the list is
 * filtered here; it is the server's list, only narrowed.
 */
export function useFeeItems() {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'catalog', 'items', ITEM_PAGE],
    queryFn: async (): Promise<ItemSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/catalog/items', { params: { query: ITEM_PAGE } });
      return data?.content ?? [];
    },
    select: (items) => items.filter((item) => item.kind === 'FEE'),
  });
}

/** A fee item's variants, once an item is picked. */
export function useItemVariants(itemId: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'catalog', 'items', itemId, 'variants'],
    queryFn: async (): Promise<ItemVariantSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/catalog/items/{id}/variants', {
        params: { path: { id: itemId! } },
      });
      return data ?? [];
    },
    enabled: !!itemId,
  });
}
