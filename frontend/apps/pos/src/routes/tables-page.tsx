import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { AlertChips } from '../features/alerts/components/alert-chips';
import { NewOrderDialog } from '../features/delivery/components/new-order-dialog';
import { TableDetailPanel } from '../features/dining/components/table-detail-panel';
import { TableMapView } from '../features/dining/components/table-map-view';
import { TableSummary } from '../features/dining/components/table-summary';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import type { DiningTable } from '../features/dining/table-map';
import { OpenTableDialog } from '../features/orders/components/open-table-dialog';
import { TableOrder } from '../features/orders/components/table-order';
import { NewReservationDialog } from '../features/reservations/components/reservation-dialog';
import { useCan } from '../features/reservations/hooks/use-can';
import { useStoreToday } from '../features/reservations/hooks/use-store-today';
import type { ReservableArea } from '../features/reservations/reservation';
import { useStoreTimeZone } from '../lib/auth/use-employee';
import { errorMessage, isOffline } from '../lib/error-messages';
import { StatusBar } from './status-bar';
import { ShortcutHints, useTablesShortcuts } from './tables-shortcuts';

/**
 * `/tables`, the POS home (§4, FC §4; light reference 2026-11-23): the store's table map, kept current by the change
 * stream, and the selected table on the right. A free table opens with its guests; an occupied one shows its open
 * order (the orders feature's `TableOrder`, composed here), sends its next round, and goes to its order to add items.
 * The footer has the store's alerts and the keyboard shortcuts: F2 a new reservation (who may book), F3 a new
 * takeaway, "/" the table search.
 */
export function TablesPage() {
  const map = useTableMap();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [opening, setOpening] = useState<DiningTable>();
  // The id only: the panel shows the table as the map has it now, and an empty panel once it is gone.
  const [selectedId, setSelectedId] = useState<string>();
  const selectedArea = map.data?.find((area) => area.tables.some((table) => table.id === selectedId));
  const selected = selectedArea?.tables.find((table) => table.id === selectedId);
  // Tables whose opening had an unknown outcome here: a later "taken" may be this terminal's own opening.
  const [mayHaveOpened, setMayHaveOpened] = useState<ReadonlySet<string>>(new Set());
  const [booking, setBooking] = useState(false);
  const [creating, setCreating] = useState(false);
  const mayBook = useCan('RESERVATION_MANAGE');
  const mapCard = useRef<HTMLElement>(null);
  useTablesShortcuts({
    reservation: mayBook ? () => setBooking(true) : undefined,
    takeaway: () => setCreating(true),
    search: () => mapCard.current?.querySelector<HTMLInputElement>('input[type="search"]')?.focus(),
  });

  /** Taken, perhaps by this terminal: the map read again, and an occupied table goes to its order. */
  async function goToOrderIfTaken(tableId: string) {
    const { data } = await map.refetch();
    const table = data?.flatMap((area) => area.tables).find((candidate) => candidate.id === tableId);
    if (table?.status === 'OCCUPIED') goToOrder(table);
  }

  /** The order the map names; a table without one goes to its own page, which says so. */
  function goToOrder(table: DiningTable) {
    navigate(table.openOrderId ? `/orders/${table.openOrderId}` : `/tables/${table.id}`);
  }

  return (
    // The screen's height, so the map and the order panel each scroll inside their own card.
    <div className="flex h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex min-h-0 flex-1 flex-col gap-3 p-4">
        {map.data ? (
          map.data.length === 0 ? (
            <section className="rounded-2xl border bg-card p-4">
              <h2 className="text-2xl font-semibold">Mesas</h2>
              <p className="py-6 text-center text-muted-foreground">
                Esta loja ainda não tem mesas. Um gestor cria as mesas no BackOffice.
              </p>
            </section>
          ) : (
            <>
              <div className="flex min-h-0 flex-1 gap-4">
                <section
                  ref={mapCard}
                  aria-labelledby="tables-title"
                  className="flex min-w-0 flex-1 flex-col gap-4 overflow-y-auto rounded-2xl border bg-card p-4"
                >
                  <h2 id="tables-title" className="sr-only">
                    Mesas
                  </h2>
                  <TableMapView
                    areas={map.data}
                    area={params.get('area') ?? undefined}
                    // The area is in the URL, so returning from a table opens the same one; switching adds no history.
                    onArea={(area) => setParams({ area }, { replace: true })}
                    selected={selected?.id}
                    onSelect={setSelectedId}
                  />
                </section>
                <TableDetailPanel
                  table={selected}
                  areaName={selectedArea?.name}
                  onOpenTable={setOpening}
                  onGoToOrder={goToOrder}
                  onClose={() => setSelectedId(undefined)}
                >
                  {selected?.openOrderId && (
                    // Keyed by the order, so another table's panel never shows this one's last send.
                    <TableOrder key={selected.openOrderId} orderId={selected.openOrderId} onAddItems={() => goToOrder(selected)} />
                  )}
                </TableDetailPanel>
              </div>
              <footer className="flex flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <TableSummary areas={map.data} />
                  <AlertChips />
                </div>
                <ShortcutHints reservation={mayBook} />
              </footer>
            </>
          )
        ) : map.isError ? (
          <div className="flex flex-col items-center gap-3 rounded-2xl border bg-card p-6 text-center">
            <p role="alert">{loadError(map.error)}</p>
            <Button variant="outline" size="touch" onClick={() => void map.refetch()}>
              Tentar novamente
            </Button>
          </div>
        ) : (
          <div role="status" aria-label="A carregar as mesas…" className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
            <Skeleton className="h-12 w-72" />
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
              {Array.from({ length: 8 }, (_, index) => (
                <Skeleton key={index} className="h-44 rounded-xl" />
              ))}
            </div>
          </div>
        )}
      </main>
      {opening && (
        <OpenTableDialog
          table={opening}
          onClose={() => setOpening(undefined)}
          onOpened={(orderId) => navigate(`/orders/${orderId}`)}
          mayHaveOpened={mayHaveOpened.has(opening.id)}
          onUnknownOutcome={() => setMayHaveOpened((tables) => new Set(tables).add(opening.id))}
          onTakenMaybeHere={() => void goToOrderIfTaken(opening.id)}
        />
      )}
      {booking && <BookingDialog areas={map.data ?? []} onClose={() => setBooking(false)} />}
      {creating && <NewOrderDialog onClose={() => setCreating(false)} onOpened={(orderId) => navigate(`/orders/${orderId}`)} />}
    </div>
  );
}

/** F2's "Nova reserva" for the store's day, as `/reservations` opens it. */
function BookingDialog({ areas, onClose }: { areas: ReservableArea[]; onClose: () => void }) {
  const today = useStoreToday(useStoreTimeZone());
  return <NewReservationDialog date={today} today={today} areas={areas} onClose={onClose} />;
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar as mesas. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar as mesas.';
  return `Não foi possível carregar as mesas. ${errorMessage(error)}`;
}
