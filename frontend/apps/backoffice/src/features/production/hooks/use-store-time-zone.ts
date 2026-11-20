import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';

/**
 * The store's timezone, to show instants in it (FC §12); undefined until known. Shares the company's store list with
 * the store selector (same key and answer), so it costs no request of its own.
 */
export function useStoreTimeZone(storeId: string): string | undefined {
  const api = useApi();
  const stores = useQuery({
    queryKey: ['backoffice', 'stores'],
    queryFn: async () =>
      (await api.GET('/api/v1/backoffice/stores', { params: { query: { page: 0, size: 100 } } })).data?.content ?? [],
  });
  return stores.data?.find((store) => store.id === storeId)?.timezone;
}
