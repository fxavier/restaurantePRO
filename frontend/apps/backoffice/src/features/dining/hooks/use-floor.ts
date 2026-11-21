import type { ApiClient } from '@restaurantpro/api-client';
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

type FloorQuery = typeof FLOOR_PAGE & { storeId: string };
type FloorPage<T> = { data?: { content?: T[]; totalElements?: number } };

/** One store's areas or tables, first page of 200. */
function useFloorList<T>(key: readonly string[], storeId: string, read: (api: ApiClient, query: FloorQuery) => Promise<FloorPage<T>>) {
  const api = useApi();
  return useQuery({
    queryKey: [...key, storeId],
    queryFn: async (): Promise<Listed<T>> => {
      const { data } = await read(api, { storeId, ...FLOOR_PAGE });
      return { items: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
  });
}

export function useAreas(storeId: string) {
  return useFloorList<RoomAreaSummary>(AREAS_KEY, storeId, (api, query) =>
    api.GET('/api/v1/backoffice/areas', { params: { query } }),
  );
}

export function useTables(storeId: string) {
  return useFloorList<RestaurantTableSummary>(TABLES_KEY, storeId, (api, query) =>
    api.GET('/api/v1/backoffice/tables', { params: { query } }),
  );
}
