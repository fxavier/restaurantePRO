import { MapPinned } from 'lucide-react';
import { DeliveryZones } from '../features/delivery';
import { useStoreSelection } from '../features/organization';

/** `/delivery/zones?storeId=…` (§4 delivery, D128, Stitch "Zonas & Tarifários de Delivery"): the store's zones and fees. */
export function DeliveryZonesPage() {
  const { storeId } = useStoreSelection();
  return (
    <>
      <p className="w-fit rounded-sm bg-primary/15 px-2 py-0.5 text-xs font-semibold tracking-wide text-primary uppercase">
        Delivery
      </p>
      <h1 className="mt-2 text-2xl font-semibold">Zonas de entrega</h1>
      <p className="mt-1 text-muted-foreground">As zonas onde a loja entrega e o artigo de taxa que cada uma cobra.</p>
      {storeId ? (
        <DeliveryZones key={storeId} storeId={storeId} />
      ) : (
        <div className="mt-6 flex flex-col items-center gap-3 rounded-xl border bg-card p-8 text-center">
          <MapPinned aria-hidden="true" className="size-8 text-muted-foreground" />
          <p>Escolha uma loja na barra superior para ver as suas zonas de entrega.</p>
        </div>
      )}
    </>
  );
}
