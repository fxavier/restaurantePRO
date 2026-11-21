import { describe, expect, it } from 'vitest';
import { addDays, dayTitle, storeClock, storeDate, storeInstant } from './store-time';

// The tests' terminal is in America/Sao_Paulo (vite.config.ts): every answer below is the store's zone, not that one.
const MAPUTO = 'Africa/Maputo';
const LISBON = 'Europe/Lisbon';

describe('store time', () => {
  it("the store's day can differ from UTC's and from the terminal's", () => {
    expect(storeDate(new Date('2026-11-20T22:30:00Z'), MAPUTO)).toBe('2026-11-21');
    expect(storeDate(new Date('2026-11-20T21:59:00Z'), MAPUTO)).toBe('2026-11-20');
    expect(storeDate(new Date('2026-11-21T01:00:00Z'), 'America/New_York')).toBe('2026-11-20');
  });

  it("an instant on the store's clock, with the day it falls on", () => {
    expect(storeClock('2026-11-20T17:30:00Z', MAPUTO)).toEqual({ date: '2026-11-20', time: '19:30' });
    expect(storeClock('2026-11-20T22:05:00Z', MAPUTO)).toEqual({ date: '2026-11-21', time: '00:05' });
    expect(storeClock('2026-11-20T09:07:00Z', MAPUTO)).toEqual({ date: '2026-11-20', time: '11:07' });
  });

  it('moves a day across month and year ends, both ways', () => {
    expect(addDays('2026-11-20', 1)).toBe('2026-11-21');
    expect(addDays('2026-11-30', 1)).toBe('2026-12-01');
    expect(addDays('2026-12-31', 1)).toBe('2027-01-01');
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28');
    expect(addDays('2026-11-20', 0)).toBe('2026-11-20');
  });

  it("the UTC instant of a time on the store's clock", () => {
    expect(storeInstant('2026-11-20', '19:30', MAPUTO)).toBe('2026-11-20T17:30:00.000Z');
    expect(storeInstant('2026-11-21', '00:30', MAPUTO)).toBe('2026-11-20T22:30:00.000Z');
    expect(storeInstant('2026-11-20', '19:30', 'UTC')).toBe('2026-11-20T19:30:00.000Z');
    expect(storeInstant('2026-11-20', '19:30', 'Asia/Kolkata')).toBe('2026-11-20T14:00:00.000Z');
  });

  it('follows a summer-time change: the offset of the day itself, not of the instant guessed first', () => {
    // Lisbon moves to UTC+1 at 01:00 UTC on 2026-03-29; 01:30 on its clock is still winter time (UTC+0).
    expect(storeInstant('2026-03-29', '00:30', LISBON)).toBe('2026-03-29T00:30:00.000Z');
    expect(storeInstant('2026-03-29', '03:00', LISBON)).toBe('2026-03-29T02:00:00.000Z');
    expect(storeInstant('2026-10-25', '12:00', LISBON)).toBe('2026-10-25T12:00:00.000Z');
    expect(storeInstant('2026-07-01', '12:00', LISBON)).toBe('2026-07-01T11:00:00.000Z');
    // Sydney moves to UTC+11 at 16:00 UTC on 2026-10-03: 01:30 on the 4th is still UTC+10, though the first guess
    // (01:30 UTC) is already in summer time.
    expect(storeInstant('2026-10-04', '01:30', 'Australia/Sydney')).toBe('2026-10-03T15:30:00.000Z');
  });

  it('a round trip: the instant of a store time shows that time again', () => {
    const instant = storeInstant('2026-11-20', '20:15', MAPUTO);
    expect(storeClock(instant, MAPUTO)).toEqual({ date: '2026-11-20', time: '20:15' });
  });

  it('names a day in Portuguese, starting with a capital', () => {
    expect(dayTitle('2026-11-20')).toBe('Sexta-feira, 20 de novembro de 2026');
    expect(dayTitle('2026-01-01')).toBe('Quinta-feira, 1 de janeiro de 2026');
  });
});
