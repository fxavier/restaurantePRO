import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type {
  BarcodeSummary,
  FamilySummary,
  ItemPriceSummary,
  ItemSummary,
  ItemVariantSummary,
  PriceListSummary,
  TaxCategorySummary,
  UnitSummary,
} from '../types';

export const CATALOG_KEY = ['backoffice', 'catalog'] as const;

/**
 * The largest page the API serves (backend `max-page-size`, D23). The item list has no search or family filter in the
 * contract (gap CAT-1), so the screen reads one such page and filters it in the browser.
 */
export const LIST_PAGE = { page: 0, size: 200 };

export type ListPage<T> = { content: T[]; totalElements: number };

/** The company's items (one page of 200, by code). */
export function useItems() {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'items'],
    queryFn: async (): Promise<ListPage<ItemSummary>> => {
      const { data } = await api.GET('/api/v1/backoffice/catalog/items', { params: { query: LIST_PAGE } });
      return { content: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
  });
}

/** A store's price lists (each in the store's currency). */
export function usePriceLists(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'price-lists', storeId],
    queryFn: async (): Promise<PriceListSummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/price-lists', { params: { query: { storeId } } })).data ?? [],
  });
}

export function usePrices(priceListId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'price-lists', 'prices', priceListId],
    queryFn: async (): Promise<ItemPriceSummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/price-lists/{id}/prices', { params: { path: { id: priceListId } } })).data ?? [],
  });
}

/** An item's variants; not read until an item is chosen (`itemId` empty). */
export function useVariants(itemId: string) {
  const api = useApi();
  return useQuery({
    enabled: itemId !== '',
    queryKey: [...CATALOG_KEY, 'items', itemId, 'variants'],
    queryFn: async (): Promise<ItemVariantSummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/items/{id}/variants', { params: { path: { id: itemId } } })).data ?? [],
  });
}

export function useBarcodes(itemId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'items', itemId, 'barcodes'],
    queryFn: async (): Promise<BarcodeSummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/barcodes', { params: { query: { itemId } } })).data ?? [],
  });
}

export function useFamilies() {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'families'],
    queryFn: async (): Promise<FamilySummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/families', { params: { query: LIST_PAGE } })).data?.content ?? [],
  });
}

export function useUnits() {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'units'],
    queryFn: async (): Promise<UnitSummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/units', { params: { query: LIST_PAGE } })).data?.content ?? [],
  });
}

export function useTaxCategories() {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'tax-categories'],
    queryFn: async (): Promise<TaxCategorySummary[]> =>
      (await api.GET('/api/v1/backoffice/catalog/tax-categories', { params: { query: LIST_PAGE } })).data?.content ?? [],
  });
}
