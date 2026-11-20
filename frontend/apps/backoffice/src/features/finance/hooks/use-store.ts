import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StoreSummary } from '../types';

/**
 * The selected store from the company's store list, for its timezone (FC §12). Same key and answer as the store
 * selector's list in the top bar, so it is one cached request, not two.
 */
export function useStore(storeId: string): StoreSummary | undefined {
  const api = useApi();
  const stores = useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async (): Promise<StoreSummary[]> => {
      const { data } = await api.GET('/api/v1/backoffice/stores', { params: { query: { page: 0, size: 100 } } });
      return data?.content ?? [];
    },
  });
  return stores.data?.find((store) => store.id === storeId);
}
