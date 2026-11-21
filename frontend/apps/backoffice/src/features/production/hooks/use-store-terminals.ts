import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';

export type StoreTerminal = { id: string; name: string };

/**
 * The store's POS terminals that can still print (not revoked), to name them in the terminal → printer table. Read
 * from the organization endpoint the terminals screen uses; one page large enough for a store.
 */
export function useStoreTerminals(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'terminals', storeId, 'printing'],
    queryFn: async (): Promise<StoreTerminal[]> => {
      const { data } = await api.GET('/api/v1/backoffice/terminals', { params: { query: { storeId, page: 0, size: 100 } } });
      return (data?.content ?? [])
        .filter((terminal) => terminal.kind === 'POS' && terminal.status !== 'REVOKED')
        .map(({ id, name }) => ({ id, name }));
    },
  });
}
