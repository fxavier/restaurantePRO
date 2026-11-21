import type { RestaurantTableSummary, RoomAreaSummary } from './types';

export type AreaFloor = { area: RoomAreaSummary; tables: RestaurantTableSummary[]; seats: number };

/**
 * Each area with its tables, in the order the server listed them (by code), and the seats of its active tables (a
 * head count for the card, not a server total). A table whose area is not listed is left out.
 */
export function floorByArea(areas: RoomAreaSummary[], tables: RestaurantTableSummary[]): AreaFloor[] {
  return areas.map((area) => {
    const own = tables.filter((table) => table.areaId === area.id);
    const seats = own.reduce((sum, table) => (table.active ? sum + (table.seats ?? 0) : sum), 0);
    return { area, tables: own, seats };
  });
}
