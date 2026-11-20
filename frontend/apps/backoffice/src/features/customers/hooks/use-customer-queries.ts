import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { CreditView, CustomerCandidate, CustomerDetails, DebtRow, StatementView } from '../types';

/** Every customers query key starts here, so a command refreshes what it may have changed (FC §5). */
export const CUSTOMERS_KEY = ['backoffice', 'customers'] as const;
export const DEBT_PAGE_SIZE = 20;

export type DebtPage = { rows: DebtRow[]; page: number; totalElements: number };

export const customerKey = (customerId: string) => [...CUSTOMERS_KEY, customerId] as const;

/** One customer with its phones and addresses, and the version an edit sends back. */
export function useCustomer(customerId: string) {
  const api = useApi();
  return useQuery({
    queryKey: customerKey(customerId),
    queryFn: async (): Promise<CustomerDetails> =>
      (await api.GET('/api/v1/backoffice/customers/{id}', { params: { path: { id: customerId } } })).data!,
  });
}

export const creditKey = (customerId: string) => [...customerKey(customerId), 'credit'] as const;

/**
 * The credit account now: limit, debt, reserved, available and block, all computed by the server (D73). A customer
 * without an account answers 422 CREDIT_ACCOUNT_MISSING, which the tab shows as "no account", not as a failure.
 */
export function useCredit(customerId: string) {
  const api = useApi();
  return useQuery({
    queryKey: creditKey(customerId),
    queryFn: async (): Promise<CreditView> =>
      (await api.GET('/api/v1/backoffice/customers/{id}/credit', { params: { path: { id: customerId } } })).data!,
  });
}

/** The current-account statement, from the start until now (the server's defaults), with its running balance. */
export function useStatement(customerId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...creditKey(customerId), 'statement'],
    queryFn: async (): Promise<StatementView> =>
      (await api.GET('/api/v1/backoffice/customers/{id}/statement', { params: { path: { id: customerId } } })).data!,
  });
}

/** The server's candidates for a search text (at most a few, by name); never a selection (§5). */
export function useCustomerSearch(q: string | undefined) {
  const api = useApi();
  return useQuery({
    queryKey: [...CUSTOMERS_KEY, 'search', q],
    queryFn: async (): Promise<CustomerCandidate[]> =>
      (await api.GET('/api/v1/backoffice/customers', { params: { query: { q: q! } } })).data ?? [],
    enabled: q !== undefined,
  });
}

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
