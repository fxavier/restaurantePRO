import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { FamilySummary, ItemSummary, TaxCategorySummary, UnitSummary } from '../types';

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
