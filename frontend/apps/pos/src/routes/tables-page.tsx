import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useNavigate, useSearchParams } from 'react-router';
import { TableMapView } from '../features/dining/components/table-map-view';
import { useTableMap } from '../features/dining/hooks/use-table-map';
import { errorMessage, isOffline } from '../lib/error-messages';
import { StatusBar } from './status-bar';

/** `/tables`, the POS home (§4, FC §4): the store's table map, kept current by the change stream. */
export function TablesPage() {
  const map = useTableMap();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();

  return (
    <div className="flex min-h-screen flex-col bg-muted">
      <StatusBar />
      <main className="flex flex-1 flex-col gap-4 p-4">
        <h2 className="sr-only">Mesas</h2>
        {map.data ? (
          map.data.length === 0 ? (
            <p className="py-6 text-center text-muted-foreground">
              Esta loja ainda não tem mesas. Um gestor cria as mesas no BackOffice.
            </p>
          ) : (
            <TableMapView
              areas={map.data}
              area={params.get('area') ?? undefined}
              // The area is in the URL, so returning from a table opens the same one; switching adds no history.
              onArea={(area) => setParams({ area }, { replace: true })}
              onOpen={(tableId) => navigate(`/tables/${tableId}`)}
            />
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
            <div className="grid grid-cols-[repeat(auto-fill,11rem)] gap-3">
              {Array.from({ length: 8 }, (_, index) => (
                <Skeleton key={index} className="h-48 w-44 rounded-xl" />
              ))}
            </div>
          </div>
        )}
      </main>
    </div>
  );
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar as mesas. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar as mesas.';
  return `Não foi possível carregar as mesas. ${errorMessage(error)}`;
}
