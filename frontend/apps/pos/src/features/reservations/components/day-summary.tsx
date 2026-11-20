import { cn } from '@restaurantpro/ui';
import { guestsLabel, reservationsLabel } from '../labels';
import type { DaySummary as Summary, StatusFilter } from '../reservation';

const CARDS: { status: StatusFilter; label: string; dot: string }[] = [
  { status: 'ALL', label: 'Total', dot: 'bg-primary' },
  { status: 'BOOKED', label: 'Por chegar', dot: 'bg-warning' },
  { status: 'SEATED', label: 'Em sala', dot: 'bg-success' },
  { status: 'NO_SHOW', label: 'Não compareceram', dot: 'bg-destructive' },
  { status: 'CANCELLED', label: 'Canceladas', dot: 'bg-muted-foreground' },
];

/** Stitch's stat cards: the day's reservations and people, in total and per state. Counts, not a capacity check. */
export function DaySummary({ summary }: { summary: Summary }) {
  return (
    <section aria-label="Resumo do dia" className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-5">
      {CARDS.map(({ status, label, dot }) => (
        <div key={status} role="group" aria-label={label} className="flex flex-col gap-1 rounded-xl border bg-card p-4">
          <p className="flex items-center gap-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
            <span aria-hidden="true" className={cn('size-2 rounded-full', dot)} />
            {label}
          </p>
          <p className="flex flex-wrap items-baseline gap-x-2">
            <span className="text-2xl font-semibold tabular-nums">{reservationsLabel(summary[status].count)}</span>
            <span aria-hidden="true" className="text-muted-foreground">
              ·
            </span>
            <span className="text-sm text-muted-foreground tabular-nums">{guestsLabel(summary[status].people)}</span>
          </p>
        </div>
      ))}
    </section>
  );
}
