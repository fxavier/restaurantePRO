import { StoreTerminals, useStoreSelection } from '../features/organization';

/** `/organization/terminals?storeId=…`: the selected store's terminals (FC §4); without a store, the way to pick one. */
export function TerminalsPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <h1 className="text-2xl font-semibold">Terminais</h1>
      <p className="mt-1 text-muted-foreground">Os terminais POS e as pontes de impressão da loja.</p>
      {storeId ? (
        <StoreTerminals key={storeId} storeId={storeId} />
      ) : (
        <p className="mt-6 rounded-lg border p-8 text-center">Escolha uma loja na barra superior para ver os seus terminais.</p>
      )}
    </>
  );
}
