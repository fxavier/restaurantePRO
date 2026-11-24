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
  const { storeId, selectStore, clearStore } = useStoreSelection();
  const only = stores.data?.length === 1 ? stores.data[0] : undefined;
  const known = stores.data?.some((store) => store.id === storeId) ?? false;
  const selected = known ? storeId : '';

  // Once the list is loaded, a single store is selected on its own and a ?storeId that is not a store of this
  // company (a stale link, another company's store) leaves the address.
  useEffect(() => {
    if (!stores.data) return;
    if (only?.id) {
      if (storeId !== only.id) selectStore(only.id);
    } else if (storeId && !known) {
      clearStore();
    }
  }, [stores.data, only?.id, storeId, known, selectStore, clearStore]);

  const icon = <Store aria-hidden="true" className="size-5 text-muted-foreground" />;
  if (stores.isError) {
    return (
      <p role="alert" className="flex items-center gap-1 text-sm">
        {icon}
        <span className="font-medium">Loja:</span>
        <span className="text-error">
          {isUnavailable(stores.error) ? 'Sem ligação' : errorMessage(stores.error)}
        </span>
      </p>
    );
  }
  // The list holds only the stores where the user holds a permission (FE-F09), so it can be empty.
  if (stores.data?.length === 0) {
    return (
      <p role="status" className="flex items-center gap-1 text-sm">
        {icon}
        <span className="font-medium">Loja:</span>
        <span className="text-muted-foreground">Não tem acesso a nenhuma loja</span>
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
          className="h-10 max-w-64 rounded-xl border border-input bg-card px-3 text-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-ring disabled:opacity-50"
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
