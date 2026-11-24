import { cn } from '@restaurantpro/ui';
import { guestsLabel, reservationsLabel } from '../labels';
import type { DaySummary as Summary, StatusFilter } from '../reservation';

/** Each state's dot in its card's colour (`STATUSES`, as the table map); decorative, the label names the state. */
const CARDS: { status: StatusFilter; label: string; dot: string }[] = [
  { status: 'ALL', label: 'Total', dot: 'bg-foreground' },
  { status: 'BOOKED', label: 'Por chegar', dot: 'bg-success' },
  { status: 'SEATED', label: 'Em sala', dot: 'bg-warning' },
  { status: 'NO_SHOW', label: 'Não compareceram', dot: 'bg-destructive' },
  { status: 'CANCELLED', label: 'Canceladas', dot: 'bg-muted-foreground' },
];

/** The day's reservations and people, in total and per state, as white cards. Counts, not a capacity check. */
export function DaySummary({ summary }: { summary: Summary }) {
  return (
    <section aria-label="Resumo do dia" className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {CARDS.map(({ status, label, dot }) => (
        <div key={status} role="group" aria-label={label} className="flex flex-col gap-0.5 rounded-2xl border bg-card px-4 py-3">
          <p className="flex items-center gap-2 text-sm font-medium text-muted-foreground">
            <span aria-hidden="true" className={cn('size-2.5 rounded-full', dot)} />
            {label}
          </p>
          <p className="text-2xl font-bold tabular-nums">{reservationsLabel(summary[status].count)}</p>
          <p className="text-sm text-muted-foreground tabular-nums">{guestsLabel(summary[status].people)}</p>
        </div>
      ))}
    </section>
  );
}
