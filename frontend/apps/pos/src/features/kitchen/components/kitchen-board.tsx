import { Badge } from '@restaurantpro/ui';
import { COLUMNS, type KitchenStatus, type KitchenTicket } from '../kitchen';
import { KitchenCard } from './kitchen-card';
import { KitchenCommands } from './kitchen-commands';

type Props = {
  tickets: KitchenTicket[];
  /** The server's kitchen delay, after which a round not ready is shown late. */
  lateAfterMinutes?: number;
  onChangedElsewhere: () => void;
};

/** The column's state as a dot beside its title (decorative: the title says it), in its cards' border colour. */
const DOT: Partial<Record<KitchenStatus, string>> = {
  QUEUED: 'bg-info-border',
  IN_PREPARATION: 'bg-warning-border',
  READY: 'bg-success-border',
};

/**
 * The kitchen display (ADR 0044; light reference): one white card per active state, each round a tinted card with its
 * commands, oldest first.
 */
export function KitchenBoard({ tickets, lateAfterMinutes, onChangedElsewhere }: Props) {
  return (
    <div className="grid flex-1 grid-cols-1 gap-4 lg:grid-cols-3">
      {COLUMNS.map(({ status, title }) => {
        const inColumn = tickets.filter((ticket) => ticket.status === status);
        const headingId = `kitchen-column-${status}`;
        return (
          <section key={status} aria-labelledby={headingId} className="flex min-w-0 flex-col gap-3 rounded-2xl border bg-card p-4">
            <h3 id={headingId} className="flex items-center gap-2 text-lg font-semibold">
              <span aria-hidden="true" className={`size-3 shrink-0 rounded-full ${DOT[status]}`} />
              {title}
              <Badge variant="secondary" className="ml-auto px-2.5 text-base tabular-nums">
                {inColumn.length}
              </Badge>
            </h3>
            {inColumn.length === 0 ? (
              <p className="py-4 text-center text-muted-foreground">Nenhuma ronda.</p>
            ) : (
              <RoundList tickets={inColumn} lateAfterMinutes={lateAfterMinutes} onChangedElsewhere={onChangedElsewhere} />
            )}
          </section>
        );
      })}
    </div>
  );
}

/** Rounds as cards, each with the commands its state allows. */
export function RoundList({
  tickets,
  lateAfterMinutes,
  onChangedElsewhere,
  className = 'flex flex-col gap-3',
}: Props & { className?: string }) {
  return (
    <ul className={className}>
      {tickets.map((ticket) => (
        <li key={ticket.id}>
          <KitchenCard ticket={ticket} lateAfterMinutes={lateAfterMinutes}>
            <KitchenCommands ticket={ticket} onChangedElsewhere={onChangedElsewhere} />
          </KitchenCard>
        </li>
      ))}
    </ul>
  );
}
