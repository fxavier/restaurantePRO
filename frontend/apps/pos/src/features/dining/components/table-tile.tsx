import { Badge } from '@restaurantpro/ui';
import { Circle, Clock, Lock, Receipt, Users } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { clockTime, guestsLabel, seatsLabel } from '../format';
import type { DiningTable } from '../table-map';

/** One fixed size for every tile, whatever its state: a table that changes never moves the map (DS: ≥ 120 × 96). */
const TILE_SIZE = 'h-48 w-44';

type Indicator = { label: string; icon: ReactNode; variant: 'destructive' | 'secondary' | 'warning' };

/**
 * A table on the map (DS "POS table states"): status by fill and label; locked, subtotal printed and reserved as
 * icon-and-label badges in that order, in a space kept for all three. The visible text is the name read out; the
 * spaces between flex items separate its words for assistive technology and take no room on screen.
 */
export function TableTile({ table, onOpen }: { table: DiningTable; onOpen: () => void }) {
  const timeZone = useStoreTimeZone();
  const occupied = table.status === 'OCCUPIED';
  const details = occupied ? occupiedDetails(table, timeZone) : [seatsLabel(table.seats)];
  const badges = indicators(table, timeZone);

  return (
    <button
      type="button"
      onClick={onOpen}
      className={`${TILE_SIZE} flex shrink-0 flex-col rounded-xl border-2 p-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 ${
        occupied
          ? 'border-primary bg-primary text-primary-foreground active:bg-primary-hover'
          : 'border-input bg-background text-foreground active:bg-muted'
      }`}
    >
      <span className="flex items-center justify-between gap-2">
        <span className="truncate text-2xl font-semibold">
          <span className="sr-only">Mesa</span> {table.code}
        </span>{' '}
        <span className="flex shrink-0 items-center gap-1 text-sm font-medium">
          {occupied ? <Users aria-hidden="true" className="size-4" /> : <Circle aria-hidden="true" className="size-4" />}
          {occupied ? 'Ocupada' : 'Livre'}
        </span>
      </span>{' '}
      {/* Two lines kept whatever the state: guests, then the time it opened (or the seats of a free table). */}
      <span className="mt-1 flex h-10 flex-col text-sm whitespace-nowrap tabular-nums">
        {details.map((detail) => (
          <Fragment key={detail}>
            <span className="truncate">{detail}</span>{' '}
          </Fragment>
        ))}
      </span>{' '}
      <span className="mt-auto flex h-[84px] flex-col items-start justify-end gap-1">
        {badges.map((badge) => (
          <Fragment key={badge.label}>
            <Badge variant={badge.variant} className="h-6 text-sm [&>svg]:size-4">
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

/** DS priority: locked, subtotal printed, reserved. */
function indicators(table: DiningTable, timeZone: string): Indicator[] {
  const badges: Indicator[] = [];
  if (table.locked) badges.push({ label: 'Bloqueada', icon: <Lock aria-hidden="true" />, variant: 'destructive' });
  if (table.subtotalPrinted) {
    badges.push({ label: 'Subtotal', icon: <Receipt aria-hidden="true" />, variant: 'secondary' });
  }
  if (table.reserved) {
    const label = table.nextReservationAt ? `Reservada ${clockTime(table.nextReservationAt, timeZone)}` : 'Reservada';
    badges.push({ label, icon: <Clock aria-hidden="true" />, variant: 'warning' });
  }
  return badges;
}
