import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { TableDetailPanel } from '../features/dining/components/table-detail-panel';
import { TableMapView } from '../features/dining/components/table-map-view';
import { TableSummary } from '../features/dining/components/table-summary';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import type { DiningTable } from '../features/dining/table-map';
import { OpenTableDialog } from '../features/orders/components/open-table-dialog';
import { errorMessage, isOffline } from '../lib/error-messages';
import { StatusBar } from './status-bar';

/**
 * `/tables`, the POS home (§4, FC §4): the store's table map, kept current by the change stream. A tap selects a table
 * for the detail panel; from there a free table opens with its guests and an occupied one goes to its order.
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
    <div className="flex min-h-screen flex-col bg-background">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4 lg:p-6">
        {map.data ? (
          map.data.length === 0 ? (
            <>
              <h2 className="text-2xl font-semibold">Mesas</h2>
              <p className="py-6 text-center text-muted-foreground">
                Esta loja ainda não tem mesas. Um gestor cria as mesas no BackOffice.
              </p>
            </>
          ) : (
            <div className="flex flex-1 gap-6">
              <div className="flex min-w-0 flex-1 flex-col gap-4">
                <TableSummary areas={map.data} />
                <h2 className="text-2xl font-semibold">Mesas</h2>
                <TableMapView
                  areas={map.data}
                  area={params.get('area') ?? undefined}
                  // The area is in the URL, so returning from a table opens the same one; switching adds no history.
                  onArea={(area) => setParams({ area }, { replace: true })}
                  selected={selected?.id}
                  onSelect={setSelectedId}
                />
              </div>
              <TableDetailPanel
                table={selected}
                areaName={selectedArea?.name}
                onOpenTable={setOpening}
                onGoToOrder={goToOrder}
                onClose={() => setSelectedId(undefined)}
              />
            </div>
          )
        ) : map.isError ? (
          <div className="flex flex-col items-center gap-3 py-6 text-center">
            <p role="alert">{loadError(map.error)}</p>
            <Button variant="outline" size="touch" onClick={() => void map.refetch()}>
              Tentar novamente
            </Button>
          </div>
        ) : (
          <div role="status" aria-label="A carregar as mesas…" className="flex flex-col gap-4">
            <Skeleton className="h-14 w-72" />
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4">
              {Array.from({ length: 8 }, (_, index) => (
                <Skeleton key={index} className="h-48 rounded-xl" />
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
    </div>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar as mesas. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar as mesas.';
  return `Não foi possível carregar as mesas. ${errorMessage(error)}`;
}
