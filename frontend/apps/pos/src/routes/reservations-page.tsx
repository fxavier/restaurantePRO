import { useTableMap } from '../features/dining/hooks/use-table-map';
import { ReservationsScreen } from '../features/reservations/components/reservations-screen';
import { StatusBar } from './status-bar';

/**
 * `/reservations` (§4, Stitch "Gestão de Reservas" and "Nova Reserva"): the store's reservations of a day. The table
 * map comes from dining and is handed in, so the reservations feature never reads another feature's internals.
 */
export function ReservationsPage() {
  const map = useTableMap();
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4 lg:p-6">
        <ReservationsScreen areas={map.data ?? []} />
      </main>
    </div>
  );
}
