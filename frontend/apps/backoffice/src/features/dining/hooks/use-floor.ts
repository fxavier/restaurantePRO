import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { RestaurantTableSummary, RoomAreaSummary } from '../types';

export const AREAS_KEY = ['backoffice', 'areas'] as const;
export const TABLES_KEY = ['backoffice', 'tables'] as const;

/**
 * The contract's largest page (`max-page-size` 200, D23), sorted by code: a store's areas and tables fit, and the screen
 * groups them, so it is not a paged table. `totalElements` says when a store holds more (simplification, no paging UI).
 */
export const FLOOR_PAGE = { page: 0, size: 200 };

export type Listed<T> = { items: T[]; totalElements: number };

export function useAreas(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...AREAS_KEY, storeId],
    queryFn: async (): Promise<Listed<RoomAreaSummary>> => {
      const { data } = await api.GET('/api/v1/backoffice/areas', { params: { query: { storeId, ...FLOOR_PAGE } } });
      return { items: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
  });
}

export function useTables(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...TABLES_KEY, storeId],
    queryFn: async (): Promise<Listed<RestaurantTableSummary>> => {
      const { data } = await api.GET('/api/v1/backoffice/tables', { params: { query: { storeId, ...FLOOR_PAGE } } });
      return { items: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
  });
}
