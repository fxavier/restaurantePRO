import { Button, DataState } from '@restaurantpro/ui';
import { Armchair, MonitorSmartphone } from 'lucide-react';
import { Link } from 'react-router';
import { isUnavailable } from '../../../lib/errors';
import { storeSearch } from '../hooks/use-store-selection';
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
                <Button asChild variant="outline" size="sm">
                  <Link to={`/organization/dining${storeSearch(store.id)}`} aria-label={`Salas & Mesas de ${store.name}`}>
                    <Armchair aria-hidden="true" />
                    Salas & Mesas
                  </Link>
                </Button>
                <Button asChild variant="outline" size="sm">
                  <Link to={`/organization/terminals${storeSearch(store.id)}`} aria-label={`Terminais de ${store.name}`}>
                    <MonitorSmartphone aria-hidden="true" />
                    Terminais
                  </Link>
                </Button>
              </div>
            </StoreCard>
          ))}
        </div>
      </DataState>
    </section>
  );
}
