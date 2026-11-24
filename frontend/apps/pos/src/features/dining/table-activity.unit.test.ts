import { describe, expect, it } from 'vitest';
import { type Activity, tileActivity, tileState, toActivity } from './table-activity';
import type { DiningTable } from './table-map';

const free: DiningTable = {
  id: 't',
  code: 'M1',
  seats: 4,
  status: 'FREE',
  guests: null,
  openedAt: null,
  openOrderId: null,
  locked: false,
  subtotalPrinted: false,
  reserved: false,
  nextReservationAt: null,
};
const occupied: DiningTable = { ...free, status: 'OCCUPIED', guests: 2, openedAt: '2026-11-18T10:00:00Z', openOrderId: 'o1' };
const total = { amount: '1250.00', currency: 'MZN' };
const now = new Date('2026-11-18T11:00:00Z');

const activity = (lastRoundAt: string | null): Activity =>
  toActivity({ overdueAfterMinutes: 30, tables: [{ tableId: 't', orderId: 'o1', total, lastRoundAt }] });

describe('a table\'s activity on the map', () => {
  it('takes the open order\'s total from the server, matched by the order the map names', () => {
    expect(tileActivity(occupied, activity('2026-11-18T10:50:00Z'), now)).toEqual({ total, minutesWithoutRound: 10, overdue: false });
  });

  it('is overdue from the server\'s threshold on, counted from the last round', () => {
    expect(tileActivity(occupied, activity('2026-11-18T10:30:00Z'), now)).toMatchObject({ minutesWithoutRound: 30, overdue: true });
    expect(tileActivity(occupied, activity('2026-11-18T10:31:00Z'), now)).toMatchObject({ overdue: false });
  });

  it('counts from the opening while no round was sent', () => {
    expect(tileActivity(occupied, activity(null), now)).toMatchObject({ minutesWithoutRound: 60, overdue: true });
  });

  it('follows the server\'s threshold, not one of its own', () => {
    const strict = toActivity({ overdueAfterMinutes: 5, tables: [{ tableId: 't', orderId: 'o1', total, lastRoundAt: '2026-11-18T10:50:00Z' }] });
    expect(tileActivity(occupied, strict, now)).toMatchObject({ overdue: true });
  });

  it('has nothing for a free table, an order the read does not have, or before the read answers', () => {
    expect(tileActivity(free, activity(null), now)).toBeUndefined();
    expect(tileActivity({ ...occupied, openOrderId: 'other' }, activity(null), now)).toBeUndefined();
    expect(tileActivity(occupied, undefined, now)).toBeUndefined();
  });
});

describe('tileState', () => {
  it('shows an overdue occupied table as overdue; locked and bill requested come first', () => {
    expect(tileState(occupied, true)).toBe('overdue');
    expect(tileState(occupied, false)).toBe('occupied');
    expect(tileState({ ...occupied, subtotalPrinted: true }, true)).toBe('bill');
    expect(tileState({ ...occupied, locked: true }, true)).toBe('locked');
  });
});
