import { cn, StatusBadge, type StatusMap } from '@restaurantpro/ui';
import { Armchair, CalendarClock, CalendarX, Phone, UserX, Users, UtensilsCrossed } from 'lucide-react';
import type { ReactNode } from 'react';
import type { Reservation, ReservationStatus } from '../reservation';
import { guestsLabel } from '../labels';

export const STATUSES: StatusMap<ReservationStatus> = {
  BOOKED: { label: 'Por chegar', icon: CalendarClock, tone: 'warning' },
  SEATED: { label: 'Sentada', icon: UtensilsCrossed, tone: 'success' },
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
 * One reservation of the day (Stitch's card): its time, name, state, party, tables, phone and notes. Its name selects
 * it for the detail panel; a selected card has the primary border.
 */
export function ReservationCard({ reservation, time, tables, selected, onSelect, children }: Props) {
  const closed = reservation.status === 'CANCELLED' || reservation.status === 'NO_SHOW';
  return (
    <article
      aria-label={`${reservation.contactName}, ${time}`}
      className={cn('flex gap-4 rounded-xl border-2 bg-card p-4', selected ? 'border-primary' : 'border-border')}
    >
      <p className="w-16 shrink-0 text-2xl font-semibold tabular-nums">{time}</p>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
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
          <StatusBadge status={reservation.status} statuses={STATUSES} />
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
