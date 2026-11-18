import { describe, expect, it } from 'vitest';
import { clockTime, guestsLabel, seatsLabel } from './format';

describe('table map formats', () => {
  it('counts guests and seats in Portuguese', () => {
    expect([guestsLabel(1), guestsLabel(3)]).toEqual(['1 pessoa', '3 pessoas']);
    expect([seatsLabel(1), seatsLabel(4)]).toEqual(['1 lugar', '4 lugares']);
  });

  it('shows an instant as a 24-hour clock time in the given zone, across midnight too', () => {
    expect(clockTime('2026-11-18T10:05:00Z', 'Africa/Maputo')).toBe('12:05');
    expect(clockTime('2026-11-18T22:30:00Z', 'Africa/Maputo')).toBe('00:30');
    expect(clockTime('2026-11-18T22:30:00Z', 'UTC')).toBe('22:30');
  });
});
