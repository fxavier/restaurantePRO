import { cn, type StatusMap, StatusPill, TONE_CARD } from '@restaurantpro/ui';
import { Armchair, CalendarClock, CalendarX, Phone, UserX, Users, UtensilsCrossed } from 'lucide-react';
import type { ReactNode } from 'react';
import type { Reservation, ReservationStatus } from '../reservation';
import { guestsLabel } from '../labels';

/**
 * Each state with the table map's tint (DESIGN.md "Light reference"): booked as a reserved table (green), seated as an
 * occupied one (amber), a no-show red, a cancellation muted.
 */
export const STATUSES: StatusMap<ReservationStatus> = {
  BOOKED: { label: 'Por chegar', icon: CalendarClock, tone: 'success' },
  SEATED: { label: 'Sentada', icon: UtensilsCrossed, tone: 'warning' },
  NO_SHOW: { label: 'Não compareceu', icon: UserX, tone: 'destructive' },
  CANCELLED: { label: 'Cancelada', icon: CalendarX, tone: 'neutral' },
};

type Props = {
  reservation: Reservation;
  /** "19:30", in the store's zone. */
  time: string;
  /** The codes of its tables ("M1 + E1"), as the map names them. */
  tables: string;
  selected: boolean;
  onSelect: () => void;
  /** The card's own action (Stitch's "Sentar"), when there is one. */
  children?: ReactNode;
};

/**
 * One reservation of the day, tinted by its state like a table tile (DESIGN.md "Light reference"): its time, name,
 * state (in words too), party, tables, phone and notes. Its name selects it for the detail panel; a selected card has
 * the tile's focus-blue ring.
 */
export function ReservationCard({ reservation, time, tables, selected, onSelect, children }: Props) {
  const closed = reservation.status === 'CANCELLED' || reservation.status === 'NO_SHOW';
  const status = STATUSES[reservation.status];
  return (
    <article
      aria-label={`${reservation.contactName}, ${time}`}
      className={cn(
        'flex items-start gap-4 rounded-xl border-2 px-4 py-3',
        TONE_CARD[status.tone],
        selected && 'ring-2 ring-ring ring-offset-2 ring-offset-card',
      )}
    >
      <p className="w-16 shrink-0 pt-2 text-2xl font-bold tabular-nums">{time}</p>
      <div className="flex min-w-0 flex-1 flex-col gap-1">
        <div className="flex flex-wrap items-center gap-2">
          <h3>
            <button
              type="button"
              aria-pressed={selected}
              onClick={onSelect}
              className={cn(
                'min-h-12 rounded-md text-left text-xl font-semibold outline-none focus-visible:ring-2 focus-visible:ring-ring',
                closed && 'text-muted-foreground line-through',
              )}
            >
              {reservation.contactName}
            </button>
          </h3>
          {/* White on the card's tint, so the pill stands out from it; the state's text colour stays AA on white. */}
          <StatusPill tone={status.tone} icon={status.icon} className="bg-card">
            {status.label}
          </StatusPill>
        </div>
        <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-muted-foreground">
          <span className="flex items-center gap-1 font-medium text-foreground">
            <Users aria-hidden="true" className="size-4" />
            {guestsLabel(reservation.partySize)}
          </span>
          {tables && (
            <span className="flex items-center gap-1">
              <Armchair aria-hidden="true" className="size-4" />
              <span className="sr-only">Mesas:</span>
              {tables}
            </span>
          )}
          {reservation.phone && (
            <span className="flex items-center gap-1 tabular-nums">
              <Phone aria-hidden="true" className="size-4" />
              <span className="sr-only">Telefone:</span>
              {reservation.phone}
            </span>
          )}
        </p>
        {reservation.notes && <p className="text-sm text-muted-foreground">{reservation.notes}</p>}
      </div>
      {children && <div className="shrink-0">{children}</div>}
    </article>
  );
}
