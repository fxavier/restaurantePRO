import { DeliveryPageLayout, ZONES_PAGE } from '../features/delivery';
import { useStoreSelection } from '../features/organization';

/** `/delivery/zones?storeId=…` (§4 delivery, D128, Stitch "Zonas & Tarifários de Delivery"): the store's zones and fees. */
export function DeliveryZonesPage() {
  return <DeliveryPageLayout page={ZONES_PAGE} storeId={useStoreSelection().storeId} />;
}
