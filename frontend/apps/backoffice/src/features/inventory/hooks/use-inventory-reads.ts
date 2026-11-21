import type { ApiClient } from '@restaurantpro/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StockRow, StoreSummary } from '../types';

/** Every inventory read starts with this key, so a command refreshes the whole area at once (FC §5). */
export const INVENTORY_KEY = ['backoffice', 'inventory'] as const;
export const STOCK_PAGE_SIZE = 50;
export const LIST_PAGE_SIZE = 50;
const BASE = '/api/v1/backoffice/inventory';

export type ListPage<Row> = { rows: Row[]; totalElements: number };
type Paged<Row> = { content?: Row[]; totalElements?: number } | undefined;
const listPage = <Row>(data: Paged<Row>): ListPage<Row> => ({ rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 });

/** One inventory read under the area's key; a list keeps its previous page on screen while the next one loads. */
function useInventoryRead<Data>(key: readonly unknown[], read: (api: ApiClient) => Promise<Data>, paged = false) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, ...key],
    queryFn: () => read(api),
    ...(paged ? { placeholderData: keepPreviousData } : {}),
  });
}

/** One page of a server-paginated list (§9). */
function useInventoryList<Row>(key: readonly unknown[], read: (api: ApiClient) => Promise<{ data?: Paged<Row> }>) {
  return useInventoryRead(key, async (api) => listPage((await read(api)).data), true);
}

const pageOf = (page: number) => ({ page, size: LIST_PAGE_SIZE });

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

export const useWarehouses = (storeId: string) =>
  useInventoryRead(['warehouses', storeId], async (api) => (await api.GET(`${BASE}/warehouses`, { params: { query: { storeId } } })).data ?? []);

/** The company's stock items (one page of up to 200, enough to pick from; a search is a follow-up). */
export const useStockItems = () =>
  useInventoryRead(
    ['stock-items'],
    async (api) => (await api.GET(`${BASE}/stock-items`, { params: { query: { page: 0, size: 200 } } })).data?.content ?? [],
  );

export const useValuation = (storeId: string) =>
  useInventoryRead(['valuation', storeId], async (api) => (await api.GET(`${BASE}/reports/valuation`, { params: { query: { storeId } } })).data ?? {});

export const useBelowMinimum = (storeId: string) =>
  useInventoryRead(
    ['below-minimum', storeId],
    async (api) => (await api.GET(`${BASE}/reports/below-minimum`, { params: { query: { storeId } } })).data ?? {},
  );

export const useSuppliers = (page = 0) =>
  useInventoryList(['suppliers', page], (api) => api.GET(`${BASE}/suppliers`, { params: { query: pageOf(page) } }));

/** The store's purchase orders in the server's order, one page at a time. */
export const usePurchaseOrders = (storeId: string, page = 0) =>
  useInventoryList(['purchase-orders', storeId, page], (api) =>
    api.GET(`${BASE}/purchase-orders`, { params: { query: { storeId, ...pageOf(page) } } }),
  );

export const useReceipts = (storeId: string, page = 0) =>
  useInventoryList(['receipts', storeId, page], (api) => api.GET(`${BASE}/receipts`, { params: { query: { storeId, ...pageOf(page) } } }));

export const useRecipes = (page = 0) =>
  useInventoryList(['recipes', page], (api) => api.GET(`${BASE}/recipes`, { params: { query: pageOf(page) } }));

export const useTransfers = (storeId: string, page = 0) =>
  useInventoryList(['transfers', storeId, page], (api) => api.GET(`${BASE}/transfers`, { params: { query: { storeId, ...pageOf(page) } } }));

/** One stock count; there is no list of counts in the API, so the screen keeps the open one in `?count=`. */
export const useCount = (countId: string) =>
  useInventoryRead(['count', countId], async (api) => (await api.GET(`${BASE}/counts/{id}`, { params: { path: { id: countId } } })).data!);

export type StockPage = { rows: StockRow[]; page: number; totalElements: number };

/** One page of a warehouse's stock, optionally only what is below its minimum. */
export function useStock(warehouseId: string | undefined, belowMinimum: boolean, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'stock', warehouseId, belowMinimum, page],
    enabled: warehouseId !== undefined,
    queryFn: async (): Promise<StockPage> => {
      const { data } = await api.GET(`${BASE}/stock`, {
        params: { query: { warehouseId: warehouseId!, ...(belowMinimum ? { belowMinimum } : {}), page, size: STOCK_PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], page, totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}

/** The stock item of an id and its name (a dash while unknown), for the documents that carry only ids. */
export function useItemLookup() {
  const items = useStockItems();
  const item = (id?: string) => items.data?.find((candidate) => candidate.id === id);
  return { item, itemName: (id?: string) => item(id)?.name ?? '—' };
}

/** Names for the ids the store's documents carry: its warehouses and the stock items. */
export function useNames(storeId: string) {
  const warehouses = useWarehouses(storeId);
  return {
    ...useItemLookup(),
    warehouseName: (id?: string, fallback = '—') => warehouses.data?.find((warehouse) => warehouse.id === id)?.name ?? fallback,
  };
}
