import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StockRow, StoreSummary, WarehouseView } from '../types';

/** Every inventory read starts with this key, so a command refreshes the whole area at once (FC §5). */
export const INVENTORY_KEY = ['backoffice', 'inventory'] as const;
export const STOCK_PAGE_SIZE = 50;

/**
 * The selected store from the company's store list, for its currency and timezone (FC §12). The same key and answer
 * as the store selector's read, so the list is fetched once.
 */
export function useStore(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async (): Promise<StoreSummary[]> =>
      (await api.GET('/api/v1/backoffice/stores', { params: { query: { page: 0, size: 100 } } })).data?.content ?? [],
    select: (stores) => stores.find((store) => store.id === storeId),
  });
}

export function useWarehouses(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'warehouses', storeId],
    queryFn: async (): Promise<WarehouseView[]> =>
      (await api.GET('/api/v1/backoffice/inventory/warehouses', { params: { query: { storeId } } })).data ?? [],
  });
}

export type StockPage = { rows: StockRow[]; page: number; totalElements: number };

/** One page of a warehouse's stock, optionally only what is below its minimum. */
export function useStock(warehouseId: string | undefined, belowMinimum: boolean, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'stock', warehouseId, belowMinimum, page],
    enabled: warehouseId !== undefined,
    queryFn: async (): Promise<StockPage> => {
      const { data } = await api.GET('/api/v1/backoffice/inventory/stock', {
        params: { query: { warehouseId: warehouseId!, ...(belowMinimum ? { belowMinimum } : {}), page, size: STOCK_PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], page, totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}
