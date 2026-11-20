import type { ApiClient } from '@restaurantpro/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { AssignmentSummary, EmployeeSummary, ListPage, RoleSummary, StoreSummary } from '../types';

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

/** One page large enough for a company's stores and roles; the pickers are not tables (simplification, no paging). */
const CHOICES = { page: 0, size: 100 };

/** Every role, for the pickers and for naming the roles of an account or an assignment. */
export function useRoleChoices() {
  const api = useApi();
  return useQuery({
    queryKey: [...PEOPLE_KEY, 'role-choices'],
    queryFn: async (): Promise<RoleSummary[]> =>
      (await api.GET('/api/v1/backoffice/roles', { params: { query: CHOICES } })).data?.content ?? [],
  });
}

/** The stores the user may act in, for the pickers and for naming an assignment's store. */
export function useStoreChoices() {
  const api = useApi();
  return useQuery({
    queryKey: [...PEOPLE_KEY, 'store-choices'],
    queryFn: async (): Promise<StoreSummary[]> =>
      (await api.GET('/api/v1/backoffice/stores', { params: { query: CHOICES } })).data?.content ?? [],
  });
}

/** The stores where an employee holds a role, each with that role (`EmployeeStoreAssignment`). */
export function useAssignments(employeeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...PEOPLE_KEY, 'assignments', employeeId],
    queryFn: async (): Promise<AssignmentSummary[]> =>
      (await api.GET('/api/v1/backoffice/employees/{id}/assignments', { params: { path: { id: employeeId } } })).data ?? [],
  });
}

/** The company's employees, or only those assigned to the store chosen in the top bar. */
export function useEmployees(storeId: string | undefined, page: number) {
  return usePagedList<EmployeeSummary>(['employees', storeId], page, async (api, query) =>
    (await api.GET('/api/v1/backoffice/employees', { params: { query: { storeId, ...query } } })).data,
  );
}
