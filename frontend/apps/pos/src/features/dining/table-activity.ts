import type { components } from '@restaurantpro/api-client';
import type { MoneyValue } from '@restaurantpro/ui';
import { minutesSince } from './format';
import type { DiningTable } from './table-map';
import { type TableState, tableState } from './table-state';

type TableActivityView = components['schemas']['com.xavier.backend.orders.model.dto.TableActivityView'];

/**
 * The order side of the map (`GET /api/v1/pos/orders/table-activity`): each seated open order's total and last round,
 * by order id, and the server's overdue threshold. Dining cannot read orders on the server (orders depends on
 * dining), so the map and this read are merged here, by the order the map names.
 */
export type Activity = {
  overdueAfterMinutes: number;
  byOrder: ReadonlyMap<string, { total: MoneyValue; lastRoundAt: string | null }>;
};

/** What a tile shows of its order: the server's total, the minutes without a round, and whether that is too long. */
export type TileActivity = { total: MoneyValue; minutesWithoutRound: number | null; overdue: boolean };

/** A tile's look: an occupied table too long without a round is overdue (red); locked and bill requested come first. */
export type TileState = TableState | 'overdue';

export function toActivity(view: TableActivityView | undefined): Activity {
  if (!view) throw new Error('Incomplete table activity');
  return {
    overdueAfterMinutes: view.overdueAfterMinutes,
    byOrder: new Map(view.tables.map((table) => [table.orderId, { total: table.total, lastRoundAt: table.lastRoundAt }])),
  };
}

/**
 * The tile's activity, or none (a free table, an order the read does not list, the read not answered): the minutes
 * since the last round, or since the table opened while no round was sent, against the server's threshold.
 */
export function tileActivity(table: DiningTable, activity: Activity | undefined, now: Date): TileActivity | undefined {
  if (table.status !== 'OCCUPIED' || !table.openOrderId) return undefined;
  const order = activity?.byOrder.get(table.openOrderId);
  if (!activity || !order) return undefined;
  const since = order.lastRoundAt ?? table.openedAt;
  const minutesWithoutRound = since ? minutesSince(since, now) : null;
  return {
    total: order.total,
    minutesWithoutRound,
    overdue: minutesWithoutRound !== null && minutesWithoutRound >= activity.overdueAfterMinutes,
  };
}

export function tileState(table: DiningTable, overdue: boolean): TileState {
  const state = tableState(table);
  return state === 'occupied' && overdue ? 'overdue' : state;
}
