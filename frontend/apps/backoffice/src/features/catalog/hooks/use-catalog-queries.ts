import type { ApiClient } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type {
  BarcodeSummary,
  FamilySummary,
  ItemPriceSummary,
  ItemSummary,
  ItemVariantSummary,
  PriceListSummary,
  ReleaseSummary,
  StoreSummary,
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

export const RELEASE_PAGE_SIZE = 20;

/** A store's releases, newest first (the server's default order); the first page, which holds the live ones. */
export function useReleases(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...CATALOG_KEY, 'releases', storeId],
    queryFn: async (): Promise<ListPage<ReleaseSummary>> => {
      const { data } = await api.GET('/api/v1/backoffice/catalog/releases', {
        params: { query: { storeId, page: 0, size: RELEASE_PAGE_SIZE } },
      });
      return { content: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
  });
}

/** The store, for its timezone (instants are shown in it, FC §12). */
export function useStore(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'stores', storeId],
    queryFn: async (): Promise<StoreSummary | undefined> =>
      (await api.GET('/api/v1/backoffice/stores/{id}', { params: { path: { id: storeId } } })).data,
  });
}

/** A catalog list under `key`, read with `read`; an absent answer is an empty list. */
function useCatalogList<T>(key: readonly string[], read: (api: ApiClient) => Promise<T[] | undefined>, enabled = true) {
  const api = useApi();
  return useQuery({ queryKey: [...CATALOG_KEY, ...key], queryFn: async (): Promise<T[]> => (await read(api)) ?? [], enabled });
}

/** A store's price lists (each in the store's currency). */
export function usePriceLists(storeId: string) {
  return useCatalogList<PriceListSummary>(['price-lists', storeId], async (api) =>
    (await api.GET('/api/v1/backoffice/catalog/price-lists', { params: { query: { storeId } } })).data,
  );
}

export function usePrices(priceListId: string) {
  return useCatalogList<ItemPriceSummary>(['price-lists', 'prices', priceListId], async (api) =>
    (await api.GET('/api/v1/backoffice/catalog/price-lists/{id}/prices', { params: { path: { id: priceListId } } })).data,
  );
}

/** An item's variants; not read until an item is chosen (`itemId` empty). */
export function useVariants(itemId: string) {
  return useCatalogList<ItemVariantSummary>(
    ['items', itemId, 'variants'],
    async (api) => (await api.GET('/api/v1/backoffice/catalog/items/{id}/variants', { params: { path: { id: itemId } } })).data,
    itemId !== '',
  );
}

export function useBarcodes(itemId: string) {
  return useCatalogList<BarcodeSummary>(['items', itemId, 'barcodes'], async (api) =>
    (await api.GET('/api/v1/backoffice/catalog/barcodes', { params: { query: { itemId } } })).data,
  );
}

/** The reference lists the item form and list need: one page of 200 each, by code. */
const REFERENCE_PAGE = { params: { query: LIST_PAGE } };
const content = <T>(answer: { data?: { content?: T[] } }) => answer.data?.content;

export const useFamilies = () => useCatalogList<FamilySummary>(['families'], async (api) => content(await api.GET('/api/v1/backoffice/catalog/families', REFERENCE_PAGE)));
export const useUnits = () => useCatalogList<UnitSummary>(['units'], async (api) => content(await api.GET('/api/v1/backoffice/catalog/units', REFERENCE_PAGE)));
export const useTaxCategories = () => useCatalogList<TaxCategorySummary>(['tax-categories'], async (api) => content(await api.GET('/api/v1/backoffice/catalog/tax-categories', REFERENCE_PAGE)));
