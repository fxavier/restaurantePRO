import { describe, expect, it } from 'vitest';
import {
  closingLines,
  fullyJustified,
  holds,
  isZero,
  linesWithDifference,
  methodLabel,
  methodsToCount,
  parseAmount,
} from './cash';
import { businessDay, clockTime } from './format';

describe('parseAmount', () => {
  it.each([
    ['1500', '1500'],
    ['1500,50', '1500.50'],
    ['1500.50', '1500.50'],
    ['  0 ', '0'],
    ['0,00', '0.00'],
    ['1234567890123456,78', '1234567890123456.78'],
  ])('%j is the decimal string %j', (typed, sent) => {
    expect(parseAmount(typed)).toBe(sent);
  });

  it.each(['', ' ', ',', '1,', ',5', '1,2,3', '1.2.3', '1,2.3', '-5', '1e3', 'abc', '1 500'])('%j is no amount', (typed) => {
    expect(parseAmount(typed)).toBeUndefined();
  });
});

describe('isZero', () => {
  it.each(['0', '0.00', '-0.00', '000'])('%j is zero', (amount) => expect(isZero(amount)).toBe(true));
  it.each(['0.01', '-25.00', '10', '1000'])('%j is not', (amount) => expect(isZero(amount)).toBe(false));
});

describe('methodsToCount', () => {
  it('puts cash first, then each other method once, whatever the order the server sent', () => {
    expect(methodsToCount([{ methodCode: 'CARD' }, { methodCode: 'CASH' }, { methodCode: 'MPESA' }, { methodCode: 'CARD' }])).toEqual([
      'CASH',
      'CARD',
      'MPESA',
    ]);
  });

  it('always counts cash, even before any movement', () => {
    expect(methodsToCount(undefined)).toEqual(['CASH']);
    expect(methodsToCount([{}])).toEqual(['CASH']);
  });
});

describe('methodLabel', () => {
  it('names cash in Portuguese and any other method by its code', () => {
    expect(methodLabel('CASH')).toBe('Numerário');
    expect(methodLabel('MPESA')).toBe('MPESA');
  });
});

describe('the closing lines', () => {
  const money = (amount: string) => ({ amount, currency: 'MZN' });
  const lines = closingLines([
    { methodCode: 'CASH', difference: money('-25.00') },
    { methodCode: 'CARD', difference: money('0.00') },
    { methodCode: 'MPESA', difference: money('3.00'), justification: 'Taxa' },
    { difference: money('9.00') },
  ]);

  it('keeps only lines that name their method', () => {
    expect(lines.map((line) => line.methodCode)).toEqual(['CASH', 'CARD', 'MPESA']);
  });

  it('a line with a difference other than zero needs a justification', () => {
    expect(linesWithDifference(lines).map((line) => line.methodCode)).toEqual(['CASH', 'MPESA']);
  });

  it('is fully justified only when each difference has a justification that is not blank', () => {
    expect(fullyJustified(lines)).toBe(false);
    const justified = lines.map((line) => ({ ...line, justification: line.justification ?? 'Contado de novo' }));
    expect(fullyJustified(justified)).toBe(true);
    expect(fullyJustified(lines.map((line) => ({ ...line, justification: '  ' })))).toBe(false);
    expect(fullyJustified(closingLines([{ methodCode: 'CASH', difference: money('0') }]))).toBe(true);
  });
});

describe('holds', () => {
  const employee = { storeId: 'S1', permissions: ['PERM_CASH_OPERATE@S1', 'PERM_CASH_DAY_CLOSE@S2'] };

  it('is true for a permission held in the employee’s own store only', () => {
    expect(holds(employee, 'CASH_OPERATE')).toBe(true);
    expect(holds(employee, 'CASH_DAY_CLOSE')).toBe(false);
    expect(holds(employee, 'CASH')).toBe(false);
    expect(holds(undefined, 'CASH_OPERATE')).toBe(false);
  });
});

describe('format', () => {
  it('shows a business day as given', () => {
    expect(businessDay('2026-11-19')).toBe('19/11/2026');
  });

  it("shows an instant's clock time in the given zone", () => {
    expect(clockTime('2026-11-19T22:30:00Z', 'Africa/Maputo')).toBe('00:30');
  });
});
