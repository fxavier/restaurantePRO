import { Store } from 'lucide-react';
import { useStoreSelection } from '../features/organization';
import { ProductionScreen } from '../features/production';

/** `/production?storeId=…` (§5, Stitch "Produção, Encaminhamento & Impressoras"); without a store, the way to pick one. */
export function ProductionPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Produção
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Produção, Encaminhamento & Impressoras</h1>
      <p className="mt-1 text-muted-foreground">
        As impressoras da loja, para onde vai cada talão de produção e a fila de impressão.
      </p>
      {storeId ? (
        <ProductionScreen key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Store aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver as suas impressoras.</p>
        </div>
      )}
    </>
  );
}
