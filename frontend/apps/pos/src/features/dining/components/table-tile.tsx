import { Money, type StatusTone, TONE_CARD, TONE_INK } from '@restaurantpro/ui';
import { Clock, Lock, Receipt, TimerOff } from 'lucide-react';
import { Fragment, type ReactNode } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { clockTime, elapsedLabel, guestsLabel, minutesSince, seatsLabel } from '../format';
import { type TileActivity, type TileState, tileState } from '../table-activity';
import type { DiningTable } from '../table-map';

/** One fixed height for every tile, whatever its state: a table that changes never moves the map (DS: ≥ 120 × 96). */
const TILE_SIZE = 'h-44 w-full';

/**
 * DESIGN.md "Light reference": each state's tone, for its tint, its 2px border and its text colour (everything on a tile
 * but the code); the detail panel's code block and the summary chips take the same.
 */
export const STATE_TONE: Record<TileState, StatusTone> = {
  free: 'success',
  occupied: 'warning',
  overdue: 'destructive',
  bill: 'info',
  locked: 'destructive',
};

type Indicator = { label: string; icon: ReactNode; variant: Extract<StatusTone, 'destructive' | 'info' | 'warning'> };

/**
 * A table on the map (DESIGN.md "Light reference"): the state by tint and border, and always in words too: "Livre"
 * shown, "Ocupada" read out (guests and minutes open say it on screen); an occupied table's total as the server
 * computed it; "N min sem ronda" when it has gone longer than the server's threshold without a round (red); locked,
 * bill requested and reserved as icon and label lines, in that order. A tap selects it for the detail panel
 * (`selected` is announced as pressed). The visible text is the name read out; the spaces between flex items separate
 * its words for assistive technology and take no room on screen.
 */
export function TableTile({
  table,
  now,
  activity,
  selected,
  onSelect,
}: {
  table: DiningTable;
  /** The map's clock, for the minutes a table has been open. */
  now: Date;
  /** The open order's total and minutes without a round; none for a free table or before the read answers. */
  activity?: TileActivity;
  selected: boolean;
  onSelect: () => void;
}) {
  const timeZone = useStoreTimeZone();
  const occupied = table.status === 'OCCUPIED';
  const state = tileState(table, activity?.overdue ?? false);
  const tone = STATE_TONE[state];
  const lines = indicators(table, timeZone);
  if (state === 'overdue' && activity?.minutesWithoutRound != null) lines.unshift(overdueIndicator(activity.minutesWithoutRound));

  return (
    <button
      type="button"
      aria-pressed={selected}
      onClick={onSelect}
      className={`${TILE_SIZE} flex flex-col overflow-hidden rounded-xl border-2 p-3 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-card ${
        TONE_CARD[tone]
      } ${TONE_INK[tone]} ${selected ? 'ring-2 ring-ring ring-offset-2 ring-offset-card' : ''}`}
    >
      <span className="min-w-0 truncate text-3xl font-bold text-foreground tabular-nums">
        <span className="sr-only">Mesa</span> {table.code}
      </span>{' '}
      <span className={`text-sm font-semibold ${occupied ? 'sr-only' : ''}`}>{occupied ? 'Ocupada' : 'Livre'}</span>{' '}
      <span className="text-sm leading-5 tabular-nums">
        {occupied ? occupiedDetails(table, now) : seatsLabel(table.seats)}
      </span>{' '}
      {activity && (
        <>
          <Money value={activity.total} className="text-base font-semibold text-foreground" />{' '}
        </>
      )}
      <span className="mt-auto flex flex-col gap-0.5 text-sm font-medium">
        {lines.map((line) => (
          <Fragment key={line.label}>
            <span className="flex items-center gap-1 truncate [&>svg]:size-4 [&>svg]:shrink-0">
              {line.icon}
              {line.label}
            </span>{' '}
          </Fragment>
        ))}
      </span>
    </button>
  );
}

/** "3 pessoas · 32 min": the guests, and the minutes since it opened (from the map's `openedAt`). */
function occupiedDetails(table: DiningTable, now: Date): string {
  const details: string[] = [];
  if (table.guests !== null) details.push(guestsLabel(table.guests));
  if (table.openedAt) details.push(elapsedLabel(minutesSince(table.openedAt, now)));
  return details.join(' · ');
}

/** "58 min sem ronda": how long an overdue table has gone without a round. */
function overdueIndicator(minutes: number): Indicator {
  return { label: `${elapsedLabel(minutes)} sem ronda`, icon: <TimerOff aria-hidden="true" />, variant: 'destructive' };
}

/** DS priority: locked, bill requested (subtotal printed), reserved; the tile's lines and the detail panel's. */
export function indicators(table: DiningTable, timeZone: string): Indicator[] {
  const badges: Indicator[] = [];
  if (table.locked) badges.push({ label: 'Bloqueada', icon: <Lock aria-hidden="true" />, variant: 'destructive' });
  if (table.subtotalPrinted) {
    badges.push({ label: 'Conta pedida', icon: <Receipt aria-hidden="true" />, variant: 'info' });
  }
  if (table.reserved) {
    const label = table.nextReservationAt ? `Reservada ${clockTime(table.nextReservationAt, timeZone)}` : 'Reservada';
    badges.push({ label, icon: <Clock aria-hidden="true" />, variant: 'warning' });
  }
  return badges;
}
