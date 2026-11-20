import { Card, CardContent, DataState } from '@restaurantpro/ui';
import { useState } from 'react';
import { useDeliveries, type DeliveryFilter } from '../hooks/use-deliveries';
import { useCouriers, useStoreTimeZone } from '../hooks/use-store-context';
import { rangeError, todayIn } from '../report-dates';
import { DeliveriesCard } from './deliveries-card';
import { ReportFilters } from './report-filters';

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
  const [filter, setFilter] = useState<DeliveryFilter>(() => {
    const today = todayIn(timeZone, new Date());
    return { from: today, to: today };
  });
  const [page, setPage] = useState(0);
  const invalid = rangeError(filter.from, filter.to);
  const deliveries = useDeliveries(storeId, filter, page, !invalid);
  const couriers = useCouriers(storeId);

  return (
    <div className="mt-6 flex flex-col gap-4">
      <Card className="gap-4">
        <CardContent>
          <ReportFilters
            filter={filter}
            onChange={(next) => {
              setFilter(next);
              setPage(0);
            }}
            couriers={couriers.data}
            error={invalid}
          />
        </CardContent>
      </Card>
      {/* Stitch's summary cards: only what the server counts; the contract has no amounts per delivery. */}
      <div role="group" aria-label="Total de entregas" className="w-fit min-w-56 rounded-xl border bg-card p-4">
        <p className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Total de entregas</p>
        <p className="text-3xl font-semibold tabular-nums">{deliveries.data?.totalElements ?? '—'}</p>
      </div>
      <DeliveriesCard
        deliveries={deliveries}
        page={page}
        onPage={setPage}
        timeZone={timeZone}
        courierName={(id) => couriers.data?.find((each) => each.id === id)?.name}
      />
    </div>
  );
}
