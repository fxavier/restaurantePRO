import type { ApiClient } from '@restaurantpro/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type {
  AssignmentSummary,
  EmployeeSummary,
  ListPage,
  PermissionView,
  RoleSummary,
  StoreSummary,
  SupportGrantSummary,
  UserSummary,
} from '../types';

/** Every people query starts with this, so a command refreshes what it may have changed in one invalidation. */
export const PEOPLE_KEY = ['backoffice', 'people'] as const;
export const PAGE_SIZE = 20;
/** One page large enough for a company's stores, roles and employees in a picker (simplification, no paging). */
const CHOICES = { page: 0, size: 100 };

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

/** A whole list at once: a picker's choices, or an employee's assignments. */
function useWholeList<Row>(key: readonly unknown[], fetchAll: (api: ApiClient) => Promise<Row[] | undefined>) {
  const api = useApi();
  return useQuery({ queryKey: [...PEOPLE_KEY, ...key], queryFn: async (): Promise<Row[]> => (await fetchAll(api)) ?? [] });
}

// The lists of the tabs, one server page each.
export const useRoles = (page: number) =>
  usePagedList<RoleSummary>(['roles'], page, async (api, query) => (await api.GET('/api/v1/backoffice/roles', { params: { query } })).data);
export const useUsers = (page: number) =>
  usePagedList<UserSummary>(['users'], page, async (api, query) => (await api.GET('/api/v1/backoffice/users', { params: { query } })).data);
export const useSupportGrants = (page: number) =>
  usePagedList<SupportGrantSummary>(['support-grants'], page, async (api, query) => (await api.GET('/api/v1/backoffice/support-grants', { params: { query } })).data);
/** The company's employees, or only those assigned to the store chosen in the top bar. */
export const useEmployees = (storeId: string | undefined, page: number) =>
  usePagedList<EmployeeSummary>(['employees', storeId], page, async (api, query) => (await api.GET('/api/v1/backoffice/employees', { params: { query: { storeId, ...query } } })).data);

// The pickers' choices, which also name the ids other rows hold.
export const useRoleChoices = () =>
  useWholeList<RoleSummary>(['role-choices'], async (api) => (await api.GET('/api/v1/backoffice/roles', { params: { query: CHOICES } })).data?.content);
export const useStoreChoices = () =>
  useWholeList<StoreSummary>(['store-choices'], async (api) => (await api.GET('/api/v1/backoffice/stores', { params: { query: CHOICES } })).data?.content);
export const useEmployeeChoices = () =>
  useWholeList<EmployeeSummary>(['employee-choices'], async (api) => (await api.GET('/api/v1/backoffice/employees', { params: { query: CHOICES } })).data?.content);
/** All the accounts, not the tab's page: the support accounts among them, and the usernames grants name by id. */
export const useAccountChoices = () =>
  useWholeList<UserSummary>(['account-choices'], async (api) => (await api.GET('/api/v1/backoffice/users', { params: { query: CHOICES } })).data?.content);

/** Every permission a role can grant (`PermissionView`): code-defined, each with its area and whether it is sensitive. */
export const usePermissionCatalogue = () =>
  useWholeList<PermissionView>(['permissions'], async (api) => (await api.GET('/api/v1/backoffice/permissions')).data);

/** The stores where an employee holds a role, each with that role (`EmployeeStoreAssignment`). */
export const useAssignments = (employeeId: string) =>
  useWholeList<AssignmentSummary>(['assignments', employeeId], async (api) => (await api.GET('/api/v1/backoffice/employees/{id}/assignments', { params: { path: { id: employeeId } } })).data);
