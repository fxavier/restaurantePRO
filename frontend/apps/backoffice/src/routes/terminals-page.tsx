import { Card, CardContent } from '@restaurantpro/ui';
import { Store } from 'lucide-react';
import { StoreTerminals, useStoreSelection } from '../features/organization';

/** `/organization/terminals?storeId=…`: the selected store's terminals (FC §4); without a store, the way to pick one. */
export function TerminalsPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      {/* Stitch's page header: the area's group as an eyebrow, then the title and what the page holds. */}
      <p className="w-fit rounded-full bg-success-tint px-2.5 py-0.5 text-xs font-semibold tracking-wide text-success-text uppercase">
        Organização
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Terminais</h1>
      <p className="mt-1 text-muted-foreground">Os terminais POS e as pontes de impressão da loja.</p>
      {storeId ? (
        <StoreTerminals key={storeId} storeId={storeId} />
      ) : (
        <Card className="mt-6">
          <CardContent className="flex flex-col items-center gap-3 py-2 text-center">
            <Store aria-hidden="true" className="size-8 text-muted-foreground" />
            <p>Escolha uma loja na barra superior para ver os seus terminais.</p>
          </CardContent>
        </Card>
      )}
    </>
  );
}
