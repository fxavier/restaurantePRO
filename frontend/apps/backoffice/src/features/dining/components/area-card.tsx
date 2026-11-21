import { Badge, Card, CardContent, CardHeader, CardTitle, StatusBadge, type StatusMap } from '@restaurantpro/ui';
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
        <StatusBadge status={area.active ? 'active' : 'inactive'} statuses={states} />
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <p className="text-sm text-muted-foreground">
          <span className="font-semibold text-foreground">{tables.length}</span> {tables.length === 1 ? 'mesa' : 'mesas'} ·{' '}
          <span className="font-semibold text-foreground">{seats}</span> lugares
        </p>
        {tables.length > 0 ? (
          <ul aria-label={`Mesas de ${area.name}`} className="flex flex-wrap gap-2">
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

function TableChip({ table }: { table: RestaurantTableSummary }) {
  return (
    <span className="flex flex-col items-start gap-0.5">
      <span className="font-mono font-semibold">{table.code}</span>
      <span className="text-xs text-muted-foreground">{table.seats} lugares</span>
      {table.locked && (
        <Badge variant="destructive">
          <Lock aria-hidden="true" />
          Bloqueada
        </Badge>
      )}
      {!table.active && (
        <Badge variant="secondary">
          <CircleOff aria-hidden="true" />
          Inativa
        </Badge>
      )}
    </span>
  );
}
