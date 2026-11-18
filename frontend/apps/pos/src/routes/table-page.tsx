import { Button } from '@restaurantpro/ui';
import { ArrowLeft } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import { StatusBar } from './status-bar';

/** `/tables/:tableId`: stands in until the order screen (issue 11). */
export function TablePage() {
  const { tableId } = useParams();
  const map = useTableMap();
  const area = map.data?.find((candidate) => candidate.tables.some((table) => table.id === tableId));
  const table = area?.tables.find((candidate) => candidate.id === tableId);

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
        <p className="text-muted-foreground">O pedido desta mesa chega no próximo passo.</p>
      </main>
    </div>
  );
}
