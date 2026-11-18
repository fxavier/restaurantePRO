import { useStores } from './use-stores';

/** The store's timezone from the store list, for showing instants (FC §12); undefined until known. */
export function useStoreTimeZone(storeId: string | undefined): string | undefined {
  return useStores().data?.find((store) => store.id === storeId)?.timezone;
}
