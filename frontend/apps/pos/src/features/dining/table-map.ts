import type { components } from '@restaurantpro/api-client';

type TableMapView = components['schemas']['com.xavier.backend.dining.model.dto.TableMap'];

/** One table as the map shows it (§4, §8.4): its status, and the indicators that apply alongside it. */
export type DiningTable = {
  id: string;
  code: string;
  seats: number;
  status: 'FREE' | 'OCCUPIED';
  guests: number | null;
  /** ISO-8601 UTC; null when FREE. */
  openedAt: string | null;
  /** The order the table is open for; null when FREE. */
  openOrderId: string | null;
  locked: boolean;
  subtotalPrinted: boolean;
  reserved: boolean;
  /** ISO-8601 UTC. */
  nextReservationAt: string | null;
};

export type DiningArea = { id: string; name: string; tables: DiningTable[] };

/** The contract marks what an area and a table always carry (FE-F28); only a missing answer is an error here. */
export function toAreas(view: TableMapView | undefined): DiningArea[] {
  if (!view) throw new Error('Incomplete table map');
  return view.areas.map((area) => ({ id: area.id, name: area.name, tables: area.tables.map(toTable) }));
}

function toTable(table: TableMapView['areas'][number]['tables'][number]): DiningTable {
  return {
    id: table.id,
    code: table.code,
    seats: table.seats,
    status: table.status,
    guests: table.guests,
    openedAt: table.openedAt,
    openOrderId: table.openOrderId,
    locked: table.locked,
    subtotalPrinted: table.subtotalPrinted,
    reserved: table.reserved,
    nextReservationAt: table.nextReservationAt,
  };
}
