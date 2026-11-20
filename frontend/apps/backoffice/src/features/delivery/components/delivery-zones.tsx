import { Badge, Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import { useFeeItems } from '../hooks/use-fee-items';
import { useZones } from '../hooks/use-zones';
import type { ZoneView } from '../types';

/** The selected store's delivery zones (§4 delivery, D128): each with its fee item. */
export function DeliveryZones({ storeId }: { storeId: string }) {
  const zones = useZones(storeId);
  const feeItems = useFeeItems();
  const feeName = (zone: ZoneView) => feeItems.data?.find((item) => item.id === zone.feeItemId)?.name;

  return (
    <div className="mt-6 flex flex-col gap-4">
      <DataState state={zones.isPending ? 'loading' : zones.data?.length ? 'ready' : 'empty'}>
        <ul aria-label="Zonas da loja" className="grid gap-4 lg:grid-cols-2">
          {zones.data?.map((zone) => (
            <li key={zone.id}>
              <ZoneCard zone={zone} feeName={feeName(zone)} />
            </li>
          ))}
        </ul>
      </DataState>
    </div>
  );
}

function ZoneCard({ zone, feeName }: { zone: ZoneView; feeName: string | undefined }) {
  return (
    <Card className="gap-3">
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <CardTitle className="text-lg" role="heading" aria-level={2}>
          <span className="font-mono text-muted-foreground">{zone.code}</span> {zone.name}
        </CardTitle>
        <Badge variant={zone.active ? 'success' : 'secondary'}>{zone.active ? 'Ativa' : 'Inativa'}</Badge>
      </CardHeader>
      <CardContent className="text-sm">
        <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Taxa de entrega</p>
        <p>{feeName}</p>
      </CardContent>
    </Card>
  );
}
