import { describe, expect, it } from 'vitest';
import { clockTime, elapsedLabel, guestsLabel, matchesTable, minutesSince, seatsLabel } from './format';

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

  it('counts the whole minutes since an instant; a terminal clock behind the server shows 0, not less', () => {
    const now = new Date('2026-11-18T10:37:59Z');
    expect(minutesSince('2026-11-18T10:05:00Z', now)).toBe(32);
    expect(minutesSince('2026-11-18T10:40:00Z', now)).toBe(0);
  });

  it('shows minutes below an hour, hours and minutes from one', () => {
    expect([elapsedLabel(0), elapsedLabel(32), elapsedLabel(60), elapsedLabel(125)]).toEqual([
      '0 min',
      '32 min',
      '1 h 00 min',
      '2 h 05 min',
    ]);
  });

  it('finds a table by its code, whatever the case and with or without a leading zero', () => {
    expect(matchesTable('M2', 'm2')).toBe(true);
    expect(matchesTable('04', '4')).toBe(true);
    expect(matchesTable('E1', ' e ')).toBe(true);
    expect(matchesTable('M12', '2')).toBe(true);
    expect(matchesTable('M1', '3')).toBe(false);
  });
});
