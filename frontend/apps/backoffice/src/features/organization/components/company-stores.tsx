import { DataState } from '@restaurantpro/ui';
import { isUnavailable } from '../../../lib/errors';
import { useStores } from '../hooks/use-stores';
import { EditStoreDialog } from './edit-store-dialog';
import { NewStoreDialog } from './new-store-dialog';
import { StoreCard } from './store-card';

/** The company's stores as cards (Stitch "Unidades Operacionais & Lojas"), and "Nova loja". */
export function CompanyStores() {
  const stores = useStores();
  const state = stores.isError
    ? isUnavailable(stores.error) ? 'unavailable' : 'error'
    : stores.isPending ? 'loading' : stores.data.length ? 'ready' : 'empty';
  // A new store most likely shares the others' timezone; the first store has the browser's.
  const defaultTimeZone = stores.data?.[0]?.timezone ?? Intl.DateTimeFormat().resolvedOptions().timeZone;
  return (
    <section aria-labelledby="stores-heading" className="mt-8 flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="stores-heading" className="text-xl font-semibold">
          Lojas
        </h2>
        {stores.data && <NewStoreDialog defaultTimeZone={defaultTimeZone} />}
      </div>
      <DataState state={state} onRetry={() => void stores.refetch()} emptyMessage="A empresa ainda não tem lojas.">
        <div className="grid gap-4 lg:grid-cols-2">
          {stores.data?.map((store) => (
            <StoreCard key={store.id} store={store}>
              <div className="flex flex-wrap gap-2">
                <EditStoreDialog store={store} />
              </div>
            </StoreCard>
          ))}
        </div>
      </DataState>
    </section>
  );
}
