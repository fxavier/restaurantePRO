import { Card, CardContent } from '@restaurantpro/ui';
import { Armchair } from 'lucide-react';
import { StoreDining } from '../features/dining';
import { useStoreSelection } from '../features/organization';

/** `/organization/dining?storeId=…` (§5): the selected store's areas, tables, reservation settings and reservations; without a store, the way to pick one. */
export function DiningPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Organização
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Salas & Mesas</h1>
      <p className="mt-1 text-muted-foreground">As áreas, as mesas e as reservas da loja.</p>
      {storeId ? (
        <StoreDining key={storeId} storeId={storeId} />
      ) : (
        <Card className="mt-6">
          <CardContent className="flex flex-col items-center gap-3 py-2 text-center">
            <Armchair aria-hidden="true" className="size-8 text-muted-foreground" />
            <p>Escolha uma loja na barra superior para ver as suas salas e mesas.</p>
          </CardContent>
        </Card>
      )}
    </>
  );
}
