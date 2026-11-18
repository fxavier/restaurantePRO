import { Store } from 'lucide-react';
import { useEffect, useId } from 'react';
import { errorMessage } from '../../../lib/error-messages';
import { isUnavailable } from '../../../lib/errors';
import { useStoreSelection } from '../hooks/use-store-selection';
import { useStores } from '../hooks/use-stores';

/**
 * The store in the top bar (story 7). A native select: the change only rewrites `?storeId`, which is harmless to
 * step through with the arrow keys. A company with one store has it selected on its own.
 */
export function StoreSelect() {
  const id = useId();
  const stores = useStores();
  const { storeId, selectStore } = useStoreSelection();
  const only = stores.data?.length === 1 ? stores.data[0] : undefined;
  const selected = stores.data?.some((store) => store.id === storeId) ? storeId : '';

  useEffect(() => {
    if (only?.id && storeId !== only.id) selectStore(only.id);
  }, [only?.id, storeId, selectStore]);

  const icon = <Store aria-hidden="true" className="size-5 text-muted-foreground" />;
  if (stores.isError) {
    return (
      <p role="alert" className="flex items-center gap-1 text-sm">
        {icon}
        <span className="font-medium">Loja:</span>
        <span className="text-destructive">
          {isUnavailable(stores.error) ? 'Sem ligação' : errorMessage(stores.error)}
        </span>
      </p>
    );
  }
  return (
    <div className="flex items-center gap-2">
      <label htmlFor={id} className="flex items-center gap-1 text-sm font-medium">
        {icon}
        Loja
      </label>
        <select
          id={id}
          value={selected}
          disabled={stores.isPending}
          onChange={(event) => event.target.value && selectStore(event.target.value)}
          className="h-9 max-w-64 rounded-md border border-input bg-background px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50"
        >
          {stores.isPending && <option value="">A carregar…</option>}
          {!stores.isPending && !selected && <option value="">Escolher loja</option>}
          {stores.data?.map((store) => (
            <option key={store.id} value={store.id}>
              {store.active === false ? `${store.name} (inativa)` : store.name}
            </option>
          ))}
        </select>
    </div>
  );
}
