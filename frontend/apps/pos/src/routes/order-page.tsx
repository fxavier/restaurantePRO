import { ApiError } from '@restaurantpro/api-client';
import { Button, ConnectionBanner, Skeleton } from '@restaurantpro/ui';
import { ArrowLeft } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import { OrderScreen } from '../features/orders/components/order-screen';
import { useOrder } from '../features/orders/hooks/use-order';
import { errorMessage, isOffline } from '../lib/error-messages';
import { useCommandsBlocked } from '../lib/live/use-connection';
import { StatusBar } from './status-bar';

/** `/orders/:orderId` (FC §4): one order's screen, under the status bar, with the way back to its table's area. */
export function OrderPage() {
  const { orderId = '' } = useParams();
  const order = useOrder(orderId);
  const map = useTableMap();
  const blocked = useCommandsBlocked();
  const tableId = order.data?.tableId;
  const area = tableId ? map.data?.find((candidate) => candidate.tables.some((table) => table.id === tableId)) : undefined;

  const back = (
    <Button asChild variant="outline" size="touch" className="shrink-0 rounded-xl bg-card">
      {/* Back to the table's own area, the one the map showed. */}
      <Link to={area ? `/tables?area=${encodeURIComponent(area.id)}` : '/tables'}>
        <ArrowLeft aria-hidden="true" />
        {/* Arrow only beside the table's title, which keeps its room; the name stays "Voltar às mesas". */}
        <span className="sr-only">Voltar às mesas</span>
      </Link>
    </Button>
  );

  return (
    <div className="flex h-screen flex-col bg-background">
      <StatusBar />
      {/* The banner takes its own row; the three panes below keep their widths. */}
      {blocked && <ConnectionBanner />}
      {order.data ? (
        <OrderScreen order={order.data} blocked={blocked} back={back} />
      ) : order.isError ? (
        <main className="m-4 flex flex-col items-center gap-3 rounded-2xl border bg-card p-6 text-center">
          {back}
          <p role="alert">{loadError(order.error)}</p>
          {!(order.error instanceof ApiError && order.error.status === 404) && (
            <Button variant="outline" size="touch" className="rounded-xl bg-card" onClick={() => void order.refetch()}>
              Tentar novamente
            </Button>
          )}
        </main>
      ) : (
        <main role="status" aria-label="A carregar o pedido…" className="grid flex-1 grid-cols-[160px_1fr_360px] gap-3 p-4">
          <Skeleton className="h-full rounded-2xl" />
          <Skeleton className="h-full rounded-2xl" />
          <Skeleton className="h-full rounded-2xl" />
        </main>
      )}
    </div>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar o pedido. A resposta do servidor está incompleta.';
  if (error.status === 404) return 'Pedido não encontrado.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar o pedido.';
  return `Não foi possível carregar o pedido. ${errorMessage(error)}`;
}
