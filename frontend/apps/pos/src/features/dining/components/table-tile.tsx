import { Badge } from '@restaurantpro/ui';
import { Circle, Clock, Lock, Receipt, Users } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { clockTime, guestsLabel, seatsLabel } from '../format';
import type { DiningTable } from '../table-map';
import { type TableState, tableState } from '../table-state';

/** One fixed height for every tile, whatever its state: a table that changes never moves the map (DS: ≥ 120 × 96). */
const TILE_SIZE = 'h-48 w-full';

/** DESIGN.md "Table states": a tint and a 2px border in the state's colour. */
const STATE_STYLE: Record<TableState, string> = {
  free: 'border-success bg-success/10',
  occupied: 'border-warning bg-warning/10',
  bill: 'border-info bg-info/10',
  locked: 'border-destructive bg-destructive/10',
};

type Indicator = { label: string; icon: ReactNode; variant: 'destructive' | 'info' | 'warning' };

/**
 * A table on the map (DESIGN.md "Table states"): the state by tint and border, the status by its pill; locked, subtotal
 * printed and reserved as icon-and-label badges in that order, in a space kept for all three. A tap selects it for the
 * detail panel (`selected` is announced as pressed). The visible text is the name read out; the
 * spaces between flex items separate its words for assistive technology and take no room on screen.
 */
export function TableTile({ table, selected, onSelect }: { table: DiningTable; selected: boolean; onSelect: () => void }) {
  const timeZone = useStoreTimeZone();
  const occupied = table.status === 'OCCUPIED';
  const details = occupied ? occupiedDetails(table, timeZone) : [seatsLabel(table.seats)];
  const badges = indicators(table, timeZone);

  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={`${TILE_SIZE} flex flex-col rounded-xl border-2 p-3 text-left text-foreground active:bg-card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background ${
        STATE_STYLE[tableState(table)]
      } ${selected ? 'ring-2 ring-ring ring-offset-2 ring-offset-background' : ''}`}
    >
      <span className="flex items-start justify-between gap-2">
        <span className="min-w-0 truncate text-3xl font-bold tabular-nums">
          <span className="sr-only">Mesa</span> {table.code}
        </span>{' '}
        <Badge variant={occupied ? 'warning' : 'success'} className="[&>svg]:size-3.5">
          {occupied ? <Users aria-hidden="true" /> : <Circle aria-hidden="true" />}
          {/* Below `sm` the word is read out but not shown: colour and icon carry it, and the code keeps its room. */}
          <span className="max-sm:sr-only">{occupied ? 'Ocupada' : 'Livre'}</span>
        </Badge>
      </span>{' '}
      {/* Two lines kept whatever the state: guests, then the time it opened (or the seats of a free table). */}
      <span className="mt-1 flex h-10 flex-col text-sm whitespace-nowrap text-muted-foreground tabular-nums">
        {details.map((detail) => (
          <Fragment key={detail}>
            <span className="truncate">{detail}</span>{' '}
          </Fragment>
        ))}
      </span>{' '}
      <span className="mt-auto flex h-20 flex-col items-start justify-end gap-1">
        {badges.map((badge) => (
          <Fragment key={badge.label}>
            <Badge variant={badge.variant} className="h-6 [&>svg]:size-3.5">
              {badge.icon}
              {badge.label}
            </Badge>{' '}
          </Fragment>
        ))}
      </span>
    </button>
  );
}

function occupiedDetails(table: DiningTable, timeZone: string): string[] {
  const details: string[] = [];
  if (table.guests !== null) details.push(guestsLabel(table.guests));
  if (table.openedAt) details.push(`desde ${clockTime(table.openedAt, timeZone)}`);
  return details;
}

/** DS priority: locked, subtotal printed, reserved; the tile's badges and the detail panel's. */
export function indicators(table: DiningTable, timeZone: string): Indicator[] {
  const badges: Indicator[] = [];
  if (table.locked) badges.push({ label: 'Bloqueada', icon: <Lock aria-hidden="true" />, variant: 'destructive' });
  if (table.subtotalPrinted) {
    badges.push({ label: 'Subtotal', icon: <Receipt aria-hidden="true" />, variant: 'info' });
  }
  if (table.reserved) {
    const label = table.nextReservationAt ? `Reservada ${clockTime(table.nextReservationAt, timeZone)}` : 'Reservada';
    badges.push({ label, icon: <Clock aria-hidden="true" />, variant: 'warning' });
  }
  return badges;
}
