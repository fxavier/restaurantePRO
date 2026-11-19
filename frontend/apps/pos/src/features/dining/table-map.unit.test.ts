import { describe, expect, it } from 'vitest';
import { MAP } from '../../test/table-map';
import { toAreas } from './table-map';

describe('toAreas', () => {
  it('maps the contract, a free table with its occupation fields null', () => {
    const [sala] = toAreas(MAP);
    expect(sala?.name).toBe('Sala');
    expect(sala?.tables[0]).toEqual({
      id: MAP.areas[0]?.tables[0]?.id,
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
    });
  });

  it('refuses a missing answer', () => {
    expect(() => toAreas(undefined)).toThrow('Incomplete table map');
  });
});
