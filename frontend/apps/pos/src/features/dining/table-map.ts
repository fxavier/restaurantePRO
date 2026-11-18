import type { components } from '@restaurantpro/api-client';

type TableMapView = components['schemas']['com.xavier.backend.dining.model.dto.TableMap'];

/** One table as the map shows it (§4, §8.4): its status, and the indicators that apply alongside it. */
export type DiningTable = {
  id: string;
  code: string;
  seats?: number;
  status: 'FREE' | 'OCCUPIED';
  guests?: number;
  /** ISO-8601 UTC. */
  openedAt?: string;
  locked: boolean;
  subtotalPrinted: boolean;
  reserved: boolean;
  /** ISO-8601 UTC. */
  nextReservationAt?: string;
};

export type DiningArea = { id: string; name: string; tables: DiningTable[] };

/**
 * The contract marks every field optional (springdoc). An area or a table without its id, its name or code, or a
 * table without its status cannot be shown or opened, so such an answer is an error rather than a guess.
 */
export function toAreas(view: TableMapView | undefined): DiningArea[] {
  if (!view || !Array.isArray(view.areas)) throw new Error('Incomplete table map');
  return view.areas.map((area) => {
    if (!area.id || area.name === undefined) throw new Error('Incomplete table map');
    return { id: area.id, name: area.name, tables: (area.tables ?? []).map(toTable) };
  });
}

function toTable(table: NonNullable<NonNullable<TableMapView['areas']>[number]['tables']>[number]): DiningTable {
  if (!table.id || table.code === undefined || !table.status) throw new Error('Incomplete table map');
  return {
    id: table.id,
    code: table.code,
    seats: table.seats,
    status: table.status,
    guests: table.guests,
    openedAt: table.openedAt,
    locked: table.locked ?? false,
    subtotalPrinted: table.subtotalPrinted ?? false,
    reserved: table.reserved ?? false,
    nextReservationAt: table.nextReservationAt,
  };
}
