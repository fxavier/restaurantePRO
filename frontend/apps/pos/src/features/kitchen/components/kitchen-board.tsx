import { Badge } from '@restaurantpro/ui';
import { COLUMNS, type KitchenTicket } from '../kitchen';
import { KitchenCard } from './kitchen-card';
import { KitchenCommands } from './kitchen-commands';

type Props = { tickets: KitchenTicket[]; onChangedElsewhere: () => void };

/** The kitchen display (ADR 0044): one column per active state, each round a card with its commands, oldest first. */
export function KitchenBoard({ tickets, onChangedElsewhere }: Props) {
  return (
    <div className="grid flex-1 grid-cols-1 gap-4 lg:grid-cols-3">
      {COLUMNS.map(({ status, title }) => {
        const inColumn = tickets.filter((ticket) => ticket.status === status);
        const headingId = `kitchen-column-${status}`;
        return (
          <section key={status} aria-labelledby={headingId} className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-3">
            <h3 id={headingId} className="flex items-center justify-between gap-2 text-xs font-semibold tracking-wide uppercase">
              {title}
              <Badge variant="secondary" className="tabular-nums">
                {inColumn.length}
              </Badge>
            </h3>
            {inColumn.length === 0 ? (
              <p className="py-4 text-center text-sm text-muted-foreground">Nenhuma ronda.</p>
            ) : (
              <RoundList tickets={inColumn} onChangedElsewhere={onChangedElsewhere} />
            )}
          </section>
        );
      })}
    </div>
  );
}

/** Rounds as cards, each with the commands its state allows. */
export function RoundList({ tickets, onChangedElsewhere, className = 'flex flex-col gap-3' }: Props & { className?: string }) {
  return (
    <ul className={className}>
      {tickets.map((ticket) => (
        <li key={ticket.id}>
          <KitchenCard ticket={ticket}>
            <KitchenCommands ticket={ticket} onChangedElsewhere={onChangedElsewhere} />
          </KitchenCard>
        </li>
      ))}
    </ul>
  );
}
