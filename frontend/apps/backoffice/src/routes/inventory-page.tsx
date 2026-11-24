import { Store } from 'lucide-react';
import { InventoryScreen } from '../features/inventory';
import { useStoreSelection } from '../features/organization';

/** `/inventory?storeId=…` (§5, Stitch "Stocks, Receitas & Compras"): the store's stock, counts, recipes and purchasing. */
export function InventoryPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Operações
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Stocks, Receitas &amp; Compras</h1>
      <p className="mt-1 text-muted-foreground">O stock por armazém, as contagens, as fichas técnicas e as compras.</p>
      {storeId ? (
        <InventoryScreen key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Store aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver o seu stock.</p>
        </div>
      )}
    </>
  );
}
