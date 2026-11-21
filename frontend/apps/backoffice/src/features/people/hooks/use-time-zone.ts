import { useStoreSelection } from '../../organization';
import { useStoreChoices } from './use-people-queries';

/**
 * The timezone to show company-wide instants in (FC §12): the store chosen in the top bar, otherwise the company's
 * first store; undefined (the browser's) until the stores are known.
 */
export function useDisplayTimeZone(): string | undefined {
  const { storeId } = useStoreSelection();
  const stores = useStoreChoices().data ?? [];
  return (stores.find((store) => store.id === storeId) ?? stores[0])?.timezone;
}
