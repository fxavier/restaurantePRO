import { Badge } from '@restaurantpro/ui';
import { Clock } from 'lucide-react';
import type { ReactNode } from 'react';
import { StationChip } from './station-chip';
import { useNow } from '../hooks/use-now';
import { type KitchenItem, type KitchenTicket, waitingMinutes } from '../kitchen';

/** One round on the display: its table and order, round, station and lines; then whatever the caller puts below. */
export function KitchenCard({ ticket, children }: { ticket: KitchenTicket; children?: ReactNode }) {
  return (
    <article aria-label={`${ticket.title}, ${ticket.round}`} className="flex flex-col gap-3 rounded-lg border bg-muted p-3">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-xl font-semibold tabular-nums">{ticket.title}</h4>
        <div className="flex items-center gap-2">
          <Badge variant="outline">{ticket.round}</Badge>
          <StationChip code={ticket.destination} />
        </div>
      </header>
      <Waiting queuedAt={ticket.queuedAt} />
      <ul className="flex flex-col gap-2">
        {ticket.items.map((item, index) => (
          <Item key={index} item={item} />
        ))}
      </ul>
      {children}
    </article>
  );
}

/** How long the round has waited since it was queued (Stitch's timer), in whole minutes. */
function Waiting({ queuedAt }: { queuedAt: string }) {
  const now = useNow();
  return (
    <p className="flex items-center gap-2 text-lg font-semibold tabular-nums">
      <Clock aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />
      Em espera há {waitingMinutes(queuedAt, now)} min
    </p>
  );
}

function Item({ item }: { item: KitchenItem }) {
  return (
    <li className="flex gap-3">
      <span className="flex h-8 min-w-10 items-center justify-center rounded-md bg-card px-2 text-lg font-bold tabular-nums">
        {item.quantity && `${item.quantity}×`}
      </span>
      <div className="flex flex-col items-start gap-1">
        <span className="text-lg font-semibold">{item.name}</span>
        {/* DESIGN.md: a status hue only as a fill with dark text. */}
        {item.notes.map((note, index) => (
          <span key={index} className="rounded-sm bg-warning px-1.5 text-sm font-semibold text-warning-foreground">
            <span className="sr-only">Nota: </span>
            {note}
          </span>
        ))}
        {item.components.map((component, index) => (
          <span key={index} className="text-muted-foreground">
            {component}
          </span>
        ))}
      </div>
    </li>
  );
}
