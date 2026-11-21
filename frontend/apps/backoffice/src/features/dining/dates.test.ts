import { describe, expect, it } from 'vitest';
import { dateIn, timeIn } from './dates';

describe('dateIn', () => {
  it("is the calendar date at that instant in the store's timezone, not the browser's", () => {
    const instant = new Date('2026-11-20T23:30:00Z');

    expect(dateIn('Africa/Maputo', instant)).toBe('2026-11-21');
    expect(dateIn('America/Sao_Paulo', instant)).toBe('2026-11-20');
    expect(dateIn('UTC', new Date('2026-01-05T00:00:00Z'))).toBe('2026-01-05');
  });
});

describe('timeIn', () => {
  it("is the hour and minute of the instant in the store's timezone", () => {
    expect(timeIn('2026-11-20T18:05:00Z', 'Africa/Maputo')).toBe('20:05');
    expect(timeIn('2026-11-20T18:05:00Z', 'UTC')).toBe('18:05');
  });
});
