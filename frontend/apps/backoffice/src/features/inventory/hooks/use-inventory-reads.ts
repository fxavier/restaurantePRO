import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type {
  BelowMinimumReport,
  PurchaseOrderView,
  ReceiptView,
  StockItemView,
  StockRow,
  StoreSummary,
  SupplierView,
  ValuationReport,
  WarehouseView,
} from '../types';

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

/** The company's stock items (one page of up to 200, enough to pick from; a search is a follow-up). */
export function useStockItems() {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'stock-items'],
    queryFn: async (): Promise<StockItemView[]> =>
      (await api.GET('/api/v1/backoffice/inventory/stock-items', { params: { query: { page: 0, size: 200 } } })).data
        ?.content ?? [],
  });
}

export function useValuation(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'valuation', storeId],
    queryFn: async (): Promise<ValuationReport> =>
      (await api.GET('/api/v1/backoffice/inventory/reports/valuation', { params: { query: { storeId } } })).data ?? {},
  });
}

export function useBelowMinimum(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'below-minimum', storeId],
    queryFn: async (): Promise<BelowMinimumReport> =>
      (await api.GET('/api/v1/backoffice/inventory/reports/below-minimum', { params: { query: { storeId } } })).data ?? {},
  });
}

export const LIST_PAGE_SIZE = 50;
export type ListPage<Row> = { rows: Row[]; totalElements: number };
type Paged<Row> = { content?: Row[]; totalElements?: number } | undefined;
const listPage = <Row,>(data: Paged<Row>): ListPage<Row> => ({ rows: data?.content ?? [], totalElements: data?.totalElements ?? 0 });

export function useSuppliers(page = 0) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'suppliers', page],
    queryFn: async (): Promise<ListPage<SupplierView>> =>
      listPage((await api.GET('/api/v1/backoffice/inventory/suppliers', { params: { query: { page, size: LIST_PAGE_SIZE } } })).data),
    placeholderData: keepPreviousData,
  });
}

/** The store's purchase orders in the server's order, one page at a time. */
export function usePurchaseOrders(storeId: string, page = 0) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'purchase-orders', storeId, page],
    queryFn: async (): Promise<ListPage<PurchaseOrderView>> =>
      listPage(
        (await api.GET('/api/v1/backoffice/inventory/purchase-orders', { params: { query: { storeId, page, size: LIST_PAGE_SIZE } } }))
          .data,
      ),
    placeholderData: keepPreviousData,
  });
}

export function useReceipts(storeId: string, page = 0) {
  const api = useApi();
  return useQuery({
    queryKey: [...INVENTORY_KEY, 'receipts', storeId, page],
    queryFn: async (): Promise<ListPage<ReceiptView>> =>
      listPage((await api.GET('/api/v1/backoffice/inventory/receipts', { params: { query: { storeId, page, size: LIST_PAGE_SIZE } } })).data),
    placeholderData: keepPreviousData,
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
