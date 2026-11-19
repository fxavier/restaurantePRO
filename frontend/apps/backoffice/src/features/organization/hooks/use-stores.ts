import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StoreSummary } from '../types';

/** One page large enough for a company's stores; the selector is not a table (simplification, no paging UI). */
const STORE_PAGE = { page: 0, size: 100 };

/** The company's stores, in Spring's flat `page`/`size` query parameters. */
export function useStores() {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async (): Promise<StoreSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/stores', {
        params: { query: STORE_PAGE },
      });
      return data?.content ?? [];
    },
  });
}
