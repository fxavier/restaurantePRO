import { Armchair } from 'lucide-react';
import { StoreFloor } from '../features/dining';
import { useStoreSelection } from '../features/organization';

/** `/organization/dining?storeId=…` (§5): the selected store's areas and tables; without a store, the way to pick one. */
export function DiningPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Organização
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Salas & Mesas</h1>
      <p className="mt-1 text-muted-foreground">As áreas, as mesas e as reservas da loja.</p>
      {storeId ? (
        <StoreFloor key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <Armchair aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver as suas salas e mesas.</p>
        </div>
      )}
    </>
  );
}
