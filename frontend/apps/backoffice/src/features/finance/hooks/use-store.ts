import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StoreSummary } from '../types';

/**
 * The selected store from the company's store list, for its timezone (FC §12). Same key and answer as the store
 * selector's list in the top bar, so it is one cached request, not two. `store` is undefined while the list loads,
 * when it fails, and when the list has no such store; `query` tells which.
 */
export function useStore(storeId: string) {
  const api = useApi();
  const query = useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async (): Promise<StoreSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/stores', { params: { query: { page: 0, size: 100 } } });
      return data?.content ?? [];
    },
  });
  return { query, store: query.data?.find((store) => store.id === storeId) };
}
