import { Button, Card, CardContent, CardHeader, CardTitle, DataState, StatusBadge, StatusPill } from '@restaurantpro/ui';
import { Pencil } from 'lucide-react';
import { useState } from 'react';
import { zoneStatuses } from '../delivery-labels';
import { useFeeItems } from '../hooks/use-fee-items';
import { useZones } from '../hooks/use-zones';
import type { ZoneView } from '../types';
import { EditZoneDialog } from './edit-zone-dialog';
import { ListError } from './list-error';
import { NewZoneDialog } from './new-zone-dialog';

/** The selected store's delivery zones (§4 delivery, D128): each with its fee item; create and edit. */
export function DeliveryZones({ storeId }: { storeId: string }) {
  const zones = useZones(storeId);
  const feeItems = useFeeItems();
  const [editingId, setEditingId] = useState<string>();
  const feeName = (zone: ZoneView) => feeItems.data?.find((item) => item.id === zone.feeItemId)?.name;

  if (zones.isError) {
    return (
      <ListError
        error={zones.error}
        forbidden="Sem permissão para ver as zonas de entrega desta loja."
        onRetry={() => void zones.refetch()}
      />
    );
  }

  return (
    <div className="mt-6 flex flex-col gap-4">
      {zones.data && <ZonesHeader storeId={storeId} zones={zones.data} />}
      <DataState
        state={listState(zones.isPending, zones.data)}
        emptyMessage="Esta loja ainda não tem zonas de entrega."
      >
        <ul aria-label="Zonas da loja" className="grid gap-4 lg:grid-cols-2">
          {zones.data?.map((zone) => (
            <li key={zone.id}>
              <ZoneCard zone={zone} feeName={feeName(zone)} onEdit={() => setEditingId(zone.id)} />
            </li>
          ))}
        </ul>
      </DataState>
      <EditZoneDialog
        storeId={storeId}
        zone={zones.data?.find((zone) => zone.id === editingId)}
        onClose={() => setEditingId(undefined)}
      />
    </div>
  );
}

function listState(pending: boolean, zones: ZoneView[] | undefined): 'loading' | 'ready' | 'empty' {
  if (pending) return 'loading';
  return zones?.length ? 'ready' : 'empty';
}

function ZonesHeader({ storeId, zones }: { storeId: string; zones: ZoneView[] }) {
  return (
    <div className="flex flex-wrap items-center gap-3">
      <h2 className="text-lg font-semibold">Zonas da loja</h2>
      {/* Stitch's count pill; a count of the server's list, not a figure of its own */}
      <StatusPill tone="neutral">{`${zones.filter((zone) => zone.active).length} ativas`}</StatusPill>
      <div className="ml-auto">
        <NewZoneDialog storeId={storeId} />
      </div>
    </div>
  );
}

type ZoneCardProps = { zone: ZoneView; feeName: string | undefined; onEdit: () => void };

function ZoneCard({ zone, feeName, onEdit }: ZoneCardProps) {
  return (
    // A white card as the POS's; the tinted pill says whether the zone is offered
    <Card className="gap-3">
      <CardHeader className="flex flex-row items-start justify-between gap-3">
        <CardTitle className="flex items-center gap-2 text-lg" role="heading" aria-level={3}>
          <span className="rounded-md border bg-muted px-1.5 font-mono text-sm text-muted-foreground">{zone.code}</span>{' '}
          {zone.name}
        </CardTitle>
        <StatusBadge status={zone.active ? 'ACTIVE' : 'INACTIVE'} statuses={zoneStatuses} look="tint" />
      </CardHeader>
      <CardContent className="flex items-end justify-between gap-3 text-sm">
        <div>
          <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Taxa de entrega</p>
          <p>{feeName}</p>
        </div>
        <Button variant="outline" size="sm" aria-label={`Editar ${zone.name}`} onClick={onEdit}>
          <Pencil aria-hidden="true" />
          Editar
        </Button>
      </CardContent>
    </Card>
  );
}
