import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { EmployeeSummary, StoreSummary } from '../types';

/**
 * The store's timezone, for showing instants (FC §12). The top bar already reads the store list under this key and
 * request, so this shares its cache rather than asking again. `settled` once the list answered (the zone is then
 * undefined only for a store the list lacks, shown in the browser's timezone).
 */
export function useStoreTimeZone(storeId: string): { timeZone: string | undefined; settled: boolean } {
  const api = useApi();
  const stores = useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async (): Promise<StoreSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/stores', { params: { query: { page: 0, size: 100 } } });
      return data?.content ?? [];
    },
  });
  return { timeZone: stores.data?.find((store) => store.id === storeId)?.timezone, settled: !stores.isPending };
}

/**
 * The store's employees, to name couriers and filter by one. DeliveryView carries only the courier's id, and the list
 * needs EMPLOYEE_MANAGE: without it the report still works, with neither names nor the filter.
 */
export function useCouriers(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'employees', storeId, 'couriers'],
    queryFn: async (): Promise<EmployeeSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/employees', {
        params: { query: { storeId, page: 0, size: 200 } },
      });
      return data?.content ?? [];
    },
    retry: false,
  });
}
