import { Badge } from '@restaurantpro/ui';
import { Clock, TimerOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { StationChip } from './station-chip';
import { useNow } from '../hooks/use-now';
import { isLate, type KitchenItem, type KitchenStatus, type KitchenTicket, waitingMinutes } from '../kitchen';

/**
 * DESIGN.md "Light reference": the table states' tints where the meaning matches. Waiting is blue (as a bill waits),
 * in preparation amber (as an occupied table), ready green, a late round red (as an overdue table); a round taken off is
 * neutral. The state is also in words: the column's title, and "Atrasada" on a late round.
 */
const STATE_STYLE: Record<KitchenStatus, string> = {
  QUEUED: 'border-info-border bg-info-tint',
  IN_PREPARATION: 'border-warning-border bg-warning-tint',
  READY: 'border-success-border bg-success-tint',
  BUMPED: 'border-border bg-muted',
};
const LATE_STYLE = 'border-destructive-border bg-destructive-tint';

type Props = {
  ticket: KitchenTicket;
  /** The server's kitchen delay; none (never late) until production's alerts answer. */
  lateAfterMinutes?: number;
  children?: ReactNode;
};

/**
 * One round on the display, readable at kitchen distance: its table and order, round and station, how long it has
 * waited, and its lines with large quantities; then whatever the caller puts below.
 */
export function KitchenCard({ ticket, lateAfterMinutes, children }: Props) {
  const now = useNow();
  const late = isLate(ticket, now, lateAfterMinutes);
  return (
    <article
      aria-label={`${ticket.title}, ${ticket.round}`}
      className={`flex flex-col gap-3 rounded-xl border-2 p-4 ${late ? LATE_STYLE : STATE_STYLE[ticket.status]}`}
    >
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-2xl font-bold tabular-nums">{ticket.title}</h4>
        <div className="flex items-center gap-2">
          <Badge variant="outline" className="bg-card">
            {ticket.round}
          </Badge>
          <StationChip code={ticket.destination} />
        </div>
      </header>
      <Waiting minutes={waitingMinutes(ticket.queuedAt, now)} late={late} />
      <ul className="flex flex-col gap-3">
        {ticket.items.map((item, index) => (
          <Item key={index} item={item} />
        ))}
      </ul>
      {children}
    </article>
  );
}

/** How long the round has waited since it was queued (Stitch's timer), in whole minutes; a late one says so in words. */
function Waiting({ minutes, late }: { minutes: number; late: boolean }) {
  return (
    <p className={`flex flex-wrap items-center gap-2 text-lg font-semibold tabular-nums ${late ? 'text-destructive-text' : ''}`}>
      {late ? (
        <TimerOff aria-hidden="true" className="size-5 shrink-0" />
      ) : (
        <Clock aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
      )}
      Em espera há {minutes} min
      {late && (
        <span className="rounded-lg bg-destructive px-2 py-0.5 text-sm font-bold text-destructive-foreground">Atrasada</span>
      )}
    </p>
  );
}

function Item({ item }: { item: KitchenItem }) {
  return (
    <li className="flex items-start gap-3">
      <span className="flex h-11 min-w-14 shrink-0 items-center justify-center rounded-lg border bg-card px-2 text-2xl font-bold tabular-nums">
        {item.quantity && `${item.quantity}×`}
      </span>
      <div className="flex min-w-0 flex-col items-start gap-1">
        <span className="text-xl leading-tight font-semibold">{item.name}</span>
        {/* DESIGN.md: a status hue only as a fill with its AA foreground. */}
        {item.notes.map((note, index) => (
          <span key={index} className="rounded-md bg-warning px-2 py-0.5 text-base font-semibold text-warning-foreground">
            <span className="sr-only">Nota: </span>
            {note}
          </span>
        ))}
        {item.components.map((component, index) => (
          <span key={index} className="text-base text-muted-foreground">
            {component.quantity && <span className="font-semibold tabular-nums">{component.quantity}× </span>}
            {component.name}
          </span>
        ))}
      </div>
    </li>
  );
}
