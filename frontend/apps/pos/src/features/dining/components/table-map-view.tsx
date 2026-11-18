import { Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import type { DiningArea } from '../table-map';
import { TableTile } from './table-tile';

type Props = {
  areas: DiningArea[];
  /** The selected area's id; the first area when absent or gone. */
  area: string | undefined;
  onArea: (id: string) => void;
  onOpen: (tableId: string) => void;
};

/** The store's tables, one tab per area (DS layouts: area tabs on top, a grid of fixed-size tiles). */
export function TableMapView({ areas, area, onArea, onOpen }: Props) {
  const active = areas.some((candidate) => candidate.id === area) ? area : areas[0]?.id;
  return (
    <Tabs value={active} onValueChange={onArea} className="gap-4">
      <TabsList className="gap-1 bg-background p-1 group-data-[orientation=horizontal]/tabs:h-auto">
        {areas.map(({ id, name }) => (
          <TabsTrigger
            key={id}
            value={id}
            className="min-h-12 px-6 text-base text-muted-foreground data-[state=active]:bg-primary data-[state=active]:text-primary-foreground"
          >
            {name}
          </TabsTrigger>
        ))}
      </TabsList>
      {areas.map(({ id, name, tables }) => (
        <TabsContent key={id} value={id}>
          {tables.length === 0 ? (
            <p className="py-6 text-muted-foreground">Sem mesas nesta área.</p>
          ) : (
            <ul aria-label={`Mesas de ${name}`} className="grid grid-cols-[repeat(auto-fill,11rem)] gap-3">
              {tables.map((table) => (
                <li key={table.id}>
                  <TableTile table={table} onOpen={() => onOpen(table.id)} />
                </li>
              ))}
            </ul>
          )}
        </TabsContent>
      ))}
    </Tabs>
  );
}
