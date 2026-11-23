import { Input, Tabs, TabsContent, TabsList, TabsTrigger } from '@restaurantpro/ui';
import { Search } from 'lucide-react';
import { useState } from 'react';
import { useNow } from '../../../lib/use-now';
import { matchesTable } from '../format';
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
 * The store's tables (the light reference's left card): one tab per area with its table count, the selected one solid
 * green, named with its count ("Sala, 12 mesas"); a "Mesa ou nº" search over the area's codes; a grid of fixed-height
 * tiles.
 */
export function TableMapView({ areas, area, onArea, selected, onSelect }: Props) {
  const active = areas.some((candidate) => candidate.id === area) ? area : areas[0]?.id;
  const [query, setQuery] = useState('');
  const now = useNow();
  return (
    <Tabs value={active} onValueChange={onArea} className="gap-4">
      <div className="flex flex-wrap items-center gap-3">
        <TabsList className="h-auto flex-1 flex-wrap justify-start gap-2 bg-transparent p-0 group-data-[orientation=horizontal]/tabs:h-auto">
          {areas.map(({ id, name, tables }) => (
            <TabsTrigger
              key={id}
              value={id}
              className="min-h-12 flex-none gap-2 rounded-xl border-border bg-card px-5 text-base text-foreground data-[state=active]:border-primary"
            >
              {name}
              {/* "Sala 12" on screen, "Sala, 12 mesas" read out; the spaces between flex items take no room. */}
              <span className="sr-only">,</span>{' '}
              <span className="text-sm font-semibold tabular-nums">{tables.length}</span>{' '}
              <span className="sr-only">{tables.length === 1 ? 'mesa' : 'mesas'}</span>
            </TabsTrigger>
          ))}
        </TabsList>
        <div role="search" className="relative w-full sm:w-52">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 left-3 size-5 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            type="search"
            aria-label="Procurar mesa"
            placeholder="Mesa ou nº"
            autoComplete="off"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="h-12 rounded-xl bg-card pl-10 text-base md:text-base"
          />
        </div>
      </div>
      {areas.map(({ id, name, tables }) => {
        const shown = tables.filter((table) => matchesTable(table.code, query));
        return (
          <TabsContent key={id} value={id}>
            {tables.length === 0 ? (
              <p className="py-6 text-muted-foreground">Sem mesas nesta área.</p>
            ) : shown.length === 0 ? (
              <p className="py-6 text-muted-foreground">Nenhuma mesa com «{query.trim()}» nesta área.</p>
            ) : (
              <ul aria-label={`Mesas de ${name}`} className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5">
                {shown.map((table) => (
                  <li key={table.id}>
                    <TableTile table={table} now={now} selected={table.id === selected} onSelect={() => onSelect(table.id)} />
                  </li>
                ))}
              </ul>
            )}
          </TabsContent>
        );
      })}
    </Tabs>
  );
}
