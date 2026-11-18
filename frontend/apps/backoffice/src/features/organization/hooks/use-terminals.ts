import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { TerminalSummary } from '../types';

export const TERMINALS_KEY = ['backoffice', 'terminals'] as const;
export const TERMINAL_PAGE_SIZE = 50;

export type TerminalPage = { terminals: TerminalSummary[]; page: number; totalElements: number };

/** One page of a store's terminals, in Spring's flat `page`/`size` parameters (as `useStores`). */
export function useTerminals(storeId: string, page: number) {
  const api = useApi();
  return useQuery({
    queryKey: [...TERMINALS_KEY, storeId, page],
    queryFn: async (): Promise<TerminalPage> => {
      const { data } = await api.GET('/api/v1/backoffice/terminals', {
        params: { query: { storeId, pageable: { page, size: TERMINAL_PAGE_SIZE } } },
        querySerializer: { object: { style: 'form', explode: true } },
      });
      return { terminals: data?.content ?? [], page, totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
  });
}
