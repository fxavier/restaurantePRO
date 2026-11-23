import type { DiningArea } from '../table-map';
import { countStates, type TableState } from '../table-state';

/** The state's tint and text, as on its tiles (DESIGN.md "Light reference"). */
const CHIPS: { state: TableState; label: string; style: string }[] = [
  { state: 'free', label: 'Livres', style: 'bg-success-tint text-success-text' },
  { state: 'occupied', label: 'Ocupadas', style: 'bg-warning-tint text-warning-text' },
  { state: 'bill', label: 'Conta pedida', style: 'bg-info-tint text-info-text' },
  { state: 'locked', label: 'Bloqueadas', style: 'bg-destructive-tint text-destructive-text' },
];

/** The store's tables by state, each table once, as chips under the map (the light reference's footer row). */
export function TableSummary({ areas }: { areas: DiningArea[] }) {
  const counts = countStates(areas);
  return (
    <ul aria-label="Resumo das mesas" className="flex flex-wrap gap-2">
      {CHIPS.map(({ state, label, style }) => (
        <li key={state} className={`flex items-center gap-2 rounded-xl px-3 py-2 text-sm font-medium ${style}`}>
          <span>{label}</span>{' '}
          <span className="font-bold tabular-nums">{counts[state]}</span>
        </li>
      ))}
    </ul>
  );
}
