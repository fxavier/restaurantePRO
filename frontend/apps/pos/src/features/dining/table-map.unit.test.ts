import { describe, expect, it } from 'vitest';
import { toAreas } from './table-map';

describe('toAreas', () => {
  it('maps the contract, indicators defaulting to off', () => {
    expect(toAreas({ areas: [{ id: 'a', name: 'Sala', tables: [{ id: 't', code: 'M1', status: 'FREE' }] }] })).toEqual([
      {
        id: 'a',
        name: 'Sala',
        tables: [{ id: 't', code: 'M1', status: 'FREE', locked: false, subtotalPrinted: false, reserved: false }],
      },
    ]);
  });

  it('refuses an answer it cannot show', () => {
    expect(() => toAreas(undefined)).toThrow('Incomplete table map');
    expect(() => toAreas({ areas: [{ name: 'Sala' }] })).toThrow('Incomplete table map');
    expect(() => toAreas({ areas: [{ id: 'a', name: 'Sala', tables: [{ id: 't', code: 'M1' }] }] })).toThrow(
      'Incomplete table map',
    );
  });
});
