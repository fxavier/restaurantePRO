import { statusPillVariants } from '@restaurantpro/ui';
import type { DiningArea } from '../table-map';
import { countStates, type TableState } from '../table-state';
import { STATE_TONE } from './table-tile';

/** Each state's chip, in its tiles' tint and text (DESIGN.md "Light reference"). */
const CHIPS: { state: TableState; label: string }[] = [
  { state: 'free', label: 'Livres' },
  { state: 'occupied', label: 'Ocupadas' },
  { state: 'bill', label: 'Conta pedida' },
  { state: 'locked', label: 'Bloqueadas' },
];

/** The store's tables by state, each table once, as chips under the map (the light reference's footer row). */
export function TableSummary({ areas }: { areas: DiningArea[] }) {
  const counts = countStates(areas);
  return (
    <ul aria-label="Resumo das mesas" className="flex flex-wrap gap-2">
      {CHIPS.map(({ state, label }) => (
        <li key={state} className={statusPillVariants({ tone: STATE_TONE[state], size: 'chip' })}>
          <span>{label}</span>{' '}
          <span className="font-bold tabular-nums">{counts[state]}</span>
        </li>
      ))}
    </ul>
  );
}
