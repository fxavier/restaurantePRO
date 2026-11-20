import type { DiningArea } from '../table-map';
import { countStates, type TableState } from '../table-state';

const CARDS: { state: TableState; label: string; dot: string }[] = [
  { state: 'free', label: 'Livres', dot: 'bg-success' },
  { state: 'occupied', label: 'Ocupadas', dot: 'bg-warning' },
  { state: 'bill', label: 'Conta pedida', dot: 'bg-info' },
  { state: 'locked', label: 'Bloqueadas', dot: 'bg-destructive' },
];

/** The store's tables by state (Stitch's summary cards), each table once; the dot repeats the tile's colour. */
export function TableSummary({ areas }: { areas: DiningArea[] }) {
  const counts = countStates(areas);
  return (
    <ul aria-label="Resumo das mesas" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {CARDS.map(({ state, label, dot }) => (
        <li key={state} className="flex items-center gap-3 rounded-xl border bg-card px-4 py-3">
          <span aria-hidden="true" className={`size-3 shrink-0 rounded-full ${dot}`} />
          <span className="flex flex-col">
            <span className="text-sm text-muted-foreground">{label}</span>{' '}
            <span className="text-xl font-bold tabular-nums">{counts[state]}</span>
          </span>
        </li>
      ))}
    </ul>
  );
}
