import type { DiningArea, DiningTable } from './table-map';

/** What a tile shows first (DESIGN.md "Table states"): locked, then bill requested, then occupied, then free. */
export type TableState = 'locked' | 'bill' | 'occupied' | 'free';

export function tableState(table: DiningTable): TableState {
  if (table.locked) return 'locked';
  if (table.subtotalPrinted) return 'bill';
  return table.status === 'OCCUPIED' ? 'occupied' : 'free';
}

/** How many of the store's tables are in each state; each table counts once. */
export function countStates(areas: DiningArea[]): Record<TableState, number> {
  const counts: Record<TableState, number> = { free: 0, occupied: 0, bill: 0, locked: 0 };
  for (const table of areas.flatMap((area) => area.tables)) counts[tableState(table)] += 1;
  return counts;
}
