import { describe, expect, it } from 'vitest';
import { rangeError, withField } from './filters';
import { businessDate, lineName, storeTime } from './format';
import { adjustmentKind } from './labels';

describe('order filters', () => {
  it('sends a field only when it has a value, trimmed', () => {
    expect(withField({ status: 'OPEN' }, 'q', '  m12 ')).toEqual({ status: 'OPEN', q: 'm12' });
    expect(withField({ status: 'OPEN', q: 'm12' }, 'q', '   ')).toEqual({ status: 'OPEN' });
    expect(withField({ mode: 'DELIVERY' }, 'mode', '')).toEqual({});
  });

  it('refuses only a period whose start is after its end; an open bound is allowed', () => {
    expect(rangeError({ from: '2026-11-21', to: '2026-11-20' })).toBe('A data inicial é posterior à final.');
    expect(rangeError({ from: '2026-11-20', to: '2026-11-20' })).toBeUndefined();
    expect(rangeError({ from: '2026-11-21' })).toBeUndefined();
    expect(rangeError({ to: '2026-11-20' })).toBeUndefined();
  });
});

describe('order formatting', () => {
  it('writes a business date without moving it through a timezone', () => {
    expect(businessDate('2026-01-01')).toBe('01/01/2026');
  });

  it("shows an instant on the store's clock, and nothing before the instant or the timezone is known", () => {
    expect(storeTime('2026-11-20T22:30:00Z', 'Africa/Maputo')).toBe('00:30');
    expect(storeTime(null, 'Africa/Maputo')).toBe('—');
    expect(storeTime('2026-11-20T22:30:00Z', undefined)).toBe('—');
  });

  it('names a line by item and variant, falling back to its code', () => {
    expect(lineName({ description: 'Sumo', variantName: 'Grande', itemCode: 'S1' })).toBe('Sumo · Grande');
    expect(lineName({ description: null, variantName: null, itemCode: 'S1' })).toBe('S1');
    expect(lineName({ description: null, variantName: null, itemCode: null })).toBe('Artigo');
  });

  it('names an adjustment kind, and shows an unknown one as the server sends it', () => {
    expect(adjustmentKind('REWARD')).toBe('Recompensa');
    expect(adjustmentKind('MANUAL_PRICE')).toBe('MANUAL_PRICE');
  });
});
