import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { DebtRow } from '../types';

/** Every customers query key starts here, so a command refreshes what it may have changed (FC §5). */
export const CUSTOMERS_KEY = ['backoffice', 'customers'] as const;
export const DEBT_PAGE_SIZE = 20;

export type DebtPage = { rows: DebtRow[]; page: number; totalElements: number };

/** One page of the customers who owe, by name, with their debt by age (as of now, the server's clock). */
export function useDebts(page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...CUSTOMERS_KEY, 'debts', page],
    queryFn: async (): Promise<DebtPage> => {
      const { data } = await api.GET('/api/v1/backoffice/customers/debts', {
        params: { query: { page, size: DEBT_PAGE_SIZE } },
      });
      return { rows: data?.content ?? [], page, totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}
