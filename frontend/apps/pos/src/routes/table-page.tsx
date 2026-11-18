import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { ArrowLeft } from 'lucide-react';
import { Link, Navigate, useParams } from 'react-router';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import { useTableOrder } from '../features/orders/hooks/use-table-order';
import { errorMessage, isOffline } from '../lib/error-messages';
import { StatusBar } from './status-bar';

/** `/tables/:tableId`: an occupied table's open order, found and opened (`/orders/:orderId`). */
export function TablePage() {
  const { tableId = '' } = useParams();
  const map = useTableMap();
  const order = useTableOrder(tableId);
  const area = map.data?.find((candidate) => candidate.tables.some((table) => table.id === tableId));
  const code = area?.tables.find((candidate) => candidate.id === tableId)?.code;

  if (order.data?.id) return <Navigate to={`/orders/${order.data.id}`} replace />;
  const table = code ? `da mesa ${code}` : 'da mesa';

  return (
    <div className="flex min-h-screen flex-col bg-muted">
      <StatusBar />
      <main className="flex flex-1 flex-col items-start gap-4 p-4">
        <Button asChild variant="outline" size="touch">
          {/* Back to the table's own area, the one the map showed. */}
          <Link to={area ? `/tables?area=${encodeURIComponent(area.id)}` : '/tables'}>
            <ArrowLeft aria-hidden="true" />
            Voltar às mesas
          </Link>
        </Button>
        <h2 className="text-2xl font-semibold">{code ? `Mesa ${code}` : 'Mesa'}</h2>
        {order.data && 'truncated' in order.data && order.data.truncated ? (
          <div className="flex flex-col items-start gap-3">
            <p role="alert">{`Não foi possível encontrar o pedido ${table} entre os pedidos abertos.`}</p>
            <Button variant="outline" size="touch" onClick={() => void order.refetch()}>
              Tentar novamente
            </Button>
          </div>
        ) : order.data ? (
          <p role="alert">{code ? `A mesa ${code} já não tem um pedido aberto.` : 'A mesa já não tem um pedido aberto.'}</p>
        ) : order.isError ? (
          <div className="flex flex-col items-start gap-3">
            <p role="alert">{loadError(order.error)}</p>
            <Button variant="outline" size="touch" onClick={() => void order.refetch()}>
              Tentar novamente
            </Button>
          </div>
        ) : (
          <p role="status">A abrir o pedido da mesa…</p>
        )}
      </main>
    </div>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível encontrar o pedido da mesa.';
  if (isOffline(error)) return 'Sem ligação — não foi possível encontrar o pedido da mesa.';
  return `Não foi possível encontrar o pedido da mesa. ${errorMessage(error)}`;
}
