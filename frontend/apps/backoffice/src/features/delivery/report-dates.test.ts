import { describe, expect, it } from 'vitest';
import { rangeError, timeIn, todayIn } from './report-dates';

describe('todayIn', () => {
  it("is the store's calendar date, which can differ from UTC's", () => {
    const now = new Date('2026-11-20T22:30:00Z');
    expect(todayIn('Africa/Maputo', now)).toBe('2026-11-21');
    expect(todayIn('UTC', now)).toBe('2026-11-20');
    expect(todayIn('America/Sao_Paulo', new Date('2026-01-05T01:00:00Z'))).toBe('2026-01-04');
  });
});

describe('rangeError', () => {
  it('accepts a range of one day or more', () => {
    expect(rangeError('2026-11-20', '2026-11-20')).toBeUndefined();
    expect(rangeError('2026-11-20', '2026-11-21')).toBeUndefined();
  });

  it('refuses a range that ends before it starts', () => {
    expect(rangeError('2026-11-21', '2026-11-20')).toBe('A data inicial é posterior à final.');
  });

  it('asks for both dates', () => {
    expect(rangeError('', '2026-11-20')).toBe('Indique as duas datas.');
    expect(rangeError('2026-11-20', '')).toBe('Indique as duas datas.');
  });
});

describe('timeIn', () => {
  it("shows an instant as the store's clock time", () => {
    expect(timeIn('2026-11-20T18:05:00Z', 'Africa/Maputo')).toBe('20:05');
    expect(timeIn('2026-11-20T18:05:00Z', 'UTC')).toBe('18:05');
  });

  it('shows a dash for a step not reached', () => {
    expect(timeIn(undefined, 'Africa/Maputo')).toBe('—');
  });
});
