import type { ApiClient } from '@restaurantpro/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { EmployeeSummary, ListPage } from '../types';

/** Every people query starts with this, so a command refreshes what it may have changed in one invalidation. */
export const PEOPLE_KEY = ['backoffice', 'people'] as const;
export const PAGE_SIZE = 20;

type Paged<Row> = { content?: Row[]; totalElements?: number } | undefined;
type PageQuery = { page: number; size: number };

/** One server page of a list, in Spring's flat `page`/`size` parameters; the previous page stays while the next loads. */
function usePagedList<Row>(key: readonly unknown[], page: number, fetchPage: (api: ApiClient, query: PageQuery) => Promise<Paged<Row>>) {
  const api = useApi();
  return useQuery({
    queryKey: [...PEOPLE_KEY, ...key, page],
    queryFn: async (): Promise<ListPage<Row>> => {
      const data = await fetchPage(api, { page, size: PAGE_SIZE });
      return { rows: data?.content ?? [], page, totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}

/** The company's employees, or only those assigned to the store chosen in the top bar. */
export function useEmployees(storeId: string | undefined, page: number) {
  return usePagedList<EmployeeSummary>(['employees', storeId], page, async (api, query) =>
    (await api.GET('/api/v1/backoffice/employees', { params: { query: { storeId, ...query } } })).data,
  );
}
