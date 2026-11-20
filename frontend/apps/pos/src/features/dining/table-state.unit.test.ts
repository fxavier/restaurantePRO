import { describe, expect, it } from 'vitest';
import type { DiningTable } from './table-map';
import { countStates, tableState } from './table-state';

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
const occupied: DiningTable = { ...free, status: 'OCCUPIED', guests: 2 };

describe('tableState', () => {
  it('puts locked before bill requested before occupied', () => {
    expect(tableState({ ...occupied, locked: true, subtotalPrinted: true })).toBe('locked');
    expect(tableState({ ...occupied, subtotalPrinted: true })).toBe('bill');
    expect(tableState(occupied)).toBe('occupied');
    expect(tableState({ ...free, reserved: true })).toBe('free');
  });

  it('counts every table of every area once', () => {
    const areas = [
      { id: 'a', name: 'Sala', tables: [free, occupied, { ...occupied, subtotalPrinted: true }] },
      { id: 'b', name: 'Esplanada', tables: [{ ...occupied, locked: true, subtotalPrinted: true }, free] },
    ];
    expect(countStates(areas)).toEqual({ free: 2, occupied: 1, bill: 1, locked: 1 });
  });
});
