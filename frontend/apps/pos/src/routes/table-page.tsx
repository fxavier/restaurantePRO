import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { ArrowLeft } from 'lucide-react';
import { Link, Navigate, useParams } from 'react-router';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import { errorMessage, isOffline } from '../lib/error-messages';
import { StatusBar } from './status-bar';

/** `/tables/:tableId`: a table's open order, as the map names it (`openOrderId`), opened (`/orders/:orderId`). */
export function TablePage() {
  const { tableId = '' } = useParams();
  const map = useTableMap();
  const area = map.data?.find((candidate) => candidate.tables.some((table) => table.id === tableId));
  const table = area?.tables.find((candidate) => candidate.id === tableId);

  if (table?.openOrderId) return <Navigate to={`/orders/${table.openOrderId}`} replace />;

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
        <h2 className="text-2xl font-semibold">{table ? `Mesa ${table.code}` : 'Mesa'}</h2>
        {map.data ? (
          <p role="alert">{table ? `A mesa ${table.code} já não tem um pedido aberto.` : 'A mesa já não tem um pedido aberto.'}</p>
        ) : map.isError ? (
          <div className="flex flex-col items-start gap-3">
            <p role="alert">{loadError(map.error)}</p>
            <Button variant="outline" size="touch" onClick={() => void map.refetch()}>
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
