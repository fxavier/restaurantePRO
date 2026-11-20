import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import type { DiningArea } from '../table-map';
import { TableTile } from './table-tile';

type Props = {
  areas: DiningArea[];
  /** The selected area's id; the first area when absent or gone. */
  area: string | undefined;
  onArea: (id: string) => void;
  /** The table shown in the detail panel. */
  selected: string | undefined;
  onSelect: (tableId: string) => void;
};

/**
 * The store's tables, one pill per area with its occupied/total count (DESIGN.md: Stitch's zone pills), and a grid of
 * fixed-height tiles. The count is hidden from assistive technology, which reads the summary above instead, so a tab is
 * named by its area alone.
 */
export function TableMapView({ areas, area, onArea, selected, onSelect }: Props) {
  const active = areas.some((candidate) => candidate.id === area) ? area : areas[0]?.id;
  return (
    <Tabs value={active} onValueChange={onArea} className="gap-4">
      <TabsList className="h-auto w-full flex-wrap justify-start gap-2 rounded-xl border bg-card p-2 group-data-[orientation=horizontal]/tabs:h-auto">
        {areas.map(({ id, name, tables }) => (
          <TabsTrigger key={id} value={id} className="min-h-12 flex-none gap-3 px-5 text-base">
            {name}
            <span aria-hidden="true" className="rounded-sm border border-current px-2 text-sm font-semibold tabular-nums">
              {tables.filter((table) => table.status === 'OCCUPIED').length}/{tables.length}
            </span>
          </TabsTrigger>
        ))}
      </TabsList>
      {areas.map(({ id, name, tables }) => (
        <TabsContent key={id} value={id}>
          {tables.length === 0 ? (
            <p className="py-6 text-muted-foreground">Sem mesas nesta área.</p>
          ) : (
            <ul
              aria-label={`Mesas de ${name}`}
              className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5"
            >
              {tables.map((table) => (
                <li key={table.id}>
                  <TableTile table={table} selected={table.id === selected} onSelect={() => onSelect(table.id)} />
                </li>
              ))}
            </ul>
          )}
        </TabsContent>
      ))}
    </Tabs>
  );
}
