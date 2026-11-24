import { Badge, Card, CardContent, CardHeader, CardTitle, cn, StatusBadge, TONE_INK, type StatusMap, type StatusTone } from '@restaurantpro/ui';
import { CircleCheck, CircleOff, Lock } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import type { AreaFloor } from '../floor';
import type { RestaurantTableSummary } from '../types';

const states: StatusMap<'active' | 'inactive'> = {
  active: { label: 'Ativa', icon: CircleCheck, tone: 'success' },
  inactive: { label: 'Inativa', icon: CircleOff, tone: 'neutral' },
};

type AreaCardProps = {
  floor: AreaFloor;
  /** The area's actions (Editar, Nova mesa). */
  actions?: ReactNode;
  /** How a table shows: by default its code, seats and state; the page wraps it in its edit button. */
  renderTable?: (table: RestaurantTableSummary, content: ReactNode) => ReactNode;
};

/** One area (Stitch "Mapeamento de Áreas"): code, name, state, its tables with seats and state, and the head count. */
export function AreaCard({ floor: { area, tables, seats }, actions, renderTable = (_, content) => content }: AreaCardProps) {
  const titleId = useId();
  return (
    <Card role="article" aria-labelledby={titleId} className="gap-4">
      <CardHeader className="flex flex-row items-start gap-3">
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <Badge variant="secondary" className="w-fit font-mono">
            {area.code}
          </Badge>
          <CardTitle id={titleId} className="text-lg">
            {area.name}
          </CardTitle>
        </div>
        <StatusBadge look="tint" status={area.active ? 'active' : 'inactive'} statuses={states} />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          <span className="font-semibold text-foreground">{tables.length}</span> {tables.length === 1 ? 'mesa' : 'mesas'} ·{' '}
          <span className="font-semibold text-foreground">{seats}</span> lugares
        </p>
        {tables.length > 0 ? (
          <ul aria-label={`Mesas de ${area.name}`} className="grid grid-cols-3 gap-2">
            {tables.map((table) => (
              <li key={table.id}>{renderTable(table, <TableChip table={table} />)}</li>
            ))}
          </ul>
        ) : (
          <p className="text-sm text-muted-foreground">Esta área ainda não tem mesas.</p>
        )}
        {actions}
      </CardContent>
    </Card>
  );
}

/** In a tile the state is a line of its AA text with its icon, as the POS map's ("Livre", "Conta pedida"). */
const TILE_STATE = 'flex items-center gap-1 text-xs font-medium';

/** A table's tile tint, as the POS map's (DESIGN.md "Light reference"): locked red, inactive grey, in service white. */
export function tableTone(table: RestaurantTableSummary): StatusTone | undefined {
  if (table.locked) return 'destructive';
  return table.active ? undefined : 'neutral';
}

function TableChip({ table }: { table: RestaurantTableSummary }) {
  return (
    <span className="flex w-full min-w-0 flex-col items-start gap-1">
      <span className="w-full text-base font-semibold break-all">{table.code}</span>
      <span className="text-xs font-normal text-muted-foreground">{table.seats} lugares</span>
      {table.locked && (
        <span className={cn(TILE_STATE, TONE_INK.destructive)}>
          <Lock aria-hidden="true" className="size-3.5" />
          Bloqueada
        </span>
      )}
      {!table.active && (
        <span className={cn(TILE_STATE, TONE_INK.neutral)}>
          <CircleOff aria-hidden="true" className="size-3.5" />
          Inativa
        </span>
      )}
    </span>
  );
}
