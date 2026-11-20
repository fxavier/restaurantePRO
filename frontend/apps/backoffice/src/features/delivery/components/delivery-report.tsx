import { Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import { useState } from 'react';
import { useCouriers, useStoreTimeZone } from '../hooks/use-store-context';
import { useDeliveries, type DeliveryFilter } from '../hooks/use-deliveries';
import { todayIn } from '../report-dates';
import { DeliveriesTable } from './deliveries-table';

/** The store's deliveries by business day and courier (§4 "histórico de entregas"), once its timezone is known. */
export function DeliveryReport({ storeId }: { storeId: string }) {
  const { timeZone, settled } = useStoreTimeZone(storeId);
  if (!settled) {
    return (
      <div className="mt-6">
        <DataState state="loading">{null}</DataState>
      </div>
    );
  }
  return <Report storeId={storeId} timeZone={timeZone} />;
}

function Report({ storeId, timeZone }: { storeId: string; timeZone: string | undefined }) {
  const [filter] = useState<DeliveryFilter>(() => {
    const today = todayIn(timeZone, new Date());
    return { from: today, to: today };
  });
  const deliveries = useDeliveries(storeId, filter, 0, true);
  const couriers = useCouriers(storeId);
  const courierName = (id: string | undefined) => (id ? couriers.data?.find((each) => each.id === id)?.name : undefined);

  return (
    <div className="mt-6 flex flex-col gap-4">
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Entregas do período</CardTitle>
        </CardHeader>
        <CardContent>
          <DataState state={deliveries.isPending ? 'loading' : deliveries.data?.deliveries.length ? 'ready' : 'empty'}>
            <DeliveriesTable
              deliveries={deliveries.data?.deliveries ?? []}
              timeZone={timeZone}
              courierName={courierName}
            />
          </DataState>
        </CardContent>
      </Card>
    </div>
  );
}
