import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { Period } from '../period';
import type { SalesReport } from '../types';

export const FINANCE_KEY = ['backoffice', 'finance'] as const;

/** The sales report of the period per day (§5 reports): its rows and the server's totals. */
export function useSalesByDay(storeId: string, period: Period) {
  const api = useApi();
  return useQuery({
    queryKey: [...FINANCE_KEY, 'sales', storeId, period],
    queryFn: async (): Promise<SalesReport> => {
      const { data } = await api.GET('/api/v1/backoffice/reports/sales', {
        params: { query: { storeId, ...period, groupBy: 'day' } },
      });
      return data as SalesReport;
    },
  });
}
