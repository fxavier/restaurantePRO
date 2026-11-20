import { DataState, type DataStateValue } from '@restaurantpro/ui';
import { isUnavailable } from '../../../lib/errors';
import { floorByArea } from '../floor';
import { useAreas, useTables } from '../hooks/use-floor';
import { EditAreaDialog, NewAreaDialog } from './area-dialogs';
import { AreaCard } from './area-card';
import { EditTableDialog, NewTableDialog } from './table-dialogs';

/** The store's areas and their tables (§5 "Atendimento"; Stitch "Mapeamento de Áreas"), each editable. */
export function StoreFloor({ storeId }: { storeId: string }) {
  const areas = useAreas(storeId);
  const tables = useTables(storeId);
  const error = areas.error ?? tables.error;
  const state: DataStateValue = error
    ? isUnavailable(error) ? 'unavailable' : 'error'
    : !areas.data || !tables.data ? 'loading' : areas.data.items.length ? 'ready' : 'empty';
  const allAreas = areas.data?.items ?? [];
  const floors = tables.data ? floorByArea(allAreas, tables.data.items) : [];

  return (
    <section aria-labelledby="floor-heading" className="mt-6 flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="floor-heading" className="text-xl font-semibold">
          Áreas e mesas
        </h2>
        {areas.data && <NewAreaDialog storeId={storeId} />}
      </div>
      <DataState
        state={state}
        onRetry={() => {
          void areas.refetch();
          void tables.refetch();
        }}
        emptyMessage="Esta loja ainda não tem áreas."
      >
        <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
          {floors.map((floor) => (
            <AreaCard
              key={floor.area.id}
              floor={floor}
              renderTable={(table, content) => (
                <EditTableDialog table={table} areas={allAreas}>
                  {content}
                </EditTableDialog>
              )}
              actions={
                <div className="flex flex-wrap gap-2">
                  <EditAreaDialog area={floor.area} />
                  <NewTableDialog area={floor.area} />
                </div>
              }
            />
          ))}
        </div>
      </DataState>
    </section>
  );
}
