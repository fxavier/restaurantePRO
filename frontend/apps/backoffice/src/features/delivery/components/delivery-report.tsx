import { Button, Card, CardContent, CardHeader, CardTitle, DataState } from '@restaurantpro/ui';
import { useState } from 'react';
import { DELIVERY_PAGE_SIZE, useDeliveries, type DeliveryFilter } from '../hooks/use-deliveries';
import { useCouriers, useStoreTimeZone } from '../hooks/use-store-context';
import { rangeError, todayIn } from '../report-dates';
import { DeliveriesTable } from './deliveries-table';
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
  const courierName = (id: string | undefined) => couriers.data?.find((each) => each.id === id)?.name;
  const data = deliveries.data;
  const pages = data ? Math.ceil(data.totalElements / DELIVERY_PAGE_SIZE) : 0;

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
        <p className="text-3xl font-semibold tabular-nums">{data?.totalElements ?? '—'}</p>
      </div>
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Entregas do período</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <DataState state={deliveries.isPending ? 'loading' : data?.deliveries.length ? 'ready' : 'empty'}>
            <DeliveriesTable deliveries={data?.deliveries ?? []} timeZone={timeZone} courierName={courierName} />
            {pages > 1 && (
              <nav aria-label="Páginas" className="flex items-center justify-end gap-2 text-sm">
                <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
                  Anterior
                </Button>
                <span>
                  Página {page + 1} de {pages}
                </span>
                <Button variant="outline" size="sm" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>
                  Seguinte
                </Button>
              </nav>
            )}
          </DataState>
        </CardContent>
      </Card>
    </div>
  );
}
