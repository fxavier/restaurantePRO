import { describe, expect, it } from 'vitest';
import { BATCH_ID, LINE, ORDER, withLines } from '../../test/order';
import { quantityLabel, sameAmount, stepQuantity, toOrder } from './order';

describe('toOrder', () => {
  it('splits drafts from the rounds, each round with its confirmed lines, by round number', () => {
    const order = toOrder(ORDER);

    expect(order.drafts.map((line) => line.id)).toEqual([LINE.water.id, LINE.coffee.id]);
    expect(order.rounds).toEqual([expect.objectContaining({ id: BATCH_ID, batchNo: 1, lines: [expect.objectContaining({ id: LINE.steak.id })] })]);
    expect(order.unbatched).toEqual([]);
    expect(order.due).toEqual({ amount: '1350.00', currency: 'MZN' });
  });

  it('keeps the versions a round is confirmed with: the order\'s, the catalog\'s and the promotions\'', () => {
    expect(toOrder({ ...ORDER, promotionVersion: 3 })).toMatchObject({ version: 4, catalogVersion: 7, promotionVersion: 3 });
    expect(toOrder(ORDER).promotionVersion).toBeNull();
  });

  it('a confirmed line shows its net quantity, and one outside any round is kept apart', () => {
    const moved = { ...LINE.steak, id: 'moved', batchId: null, quantity: '2.000', netQuantity: '1.000' };
    const order = toOrder(withLines(ORDER, [moved]));

    expect(order.unbatched).toEqual([expect.objectContaining({ id: 'moved', quantity: '1.000' })]);
  });

  it('keeps reading numbers from a response stored before decimals became strings (FE-F33)', () => {
    const replayed = { ...LINE.steak, quantity: 3, netQuantity: 3, gross: { amount: 1350, currency: 'MZN' } };
    const order = toOrder(withLines(ORDER, [replayed as unknown as typeof LINE.steak]));

    expect(order.rounds[0]!.lines[0]).toMatchObject({ quantity: '3', gross: { amount: 1350, currency: 'MZN' } });
  });

  it('a missing answer is an error', () => {
    expect(() => toOrder(undefined)).toThrow('Incomplete order');
  });

  it('formats a quantity in the store locale', () => {
    expect(quantityLabel('2.000')).toBe('2');
    expect(quantityLabel('0.500')).toBe('0,5');
  });
});

describe('stepQuantity', () => {
  it('steps a decimal quantity by one unit exactly, in thousandths', () => {
    expect(stepQuantity('1.300', -1)).toBe('0.3');
    expect(stepQuantity('2.3', -1)).toBe('1.3');
    expect(stepQuantity('0.650', 1)).toBe('1.65');
    expect(stepQuantity('2.000', 1)).toBe('3');
  });

  it('a quantity must stay positive (UpdateLineRequest @Positive)', () => {
    expect(stepQuantity('1.000', -1)).toBeUndefined();
    expect(stepQuantity('0.500', -1)).toBeUndefined();
  });
});

describe('sameAmount', () => {
  it('compares decimal amounts exactly, as text, whatever their scale or JSON type', () => {
    expect(sameAmount('120.00', 120)).toBe(true);
    expect(sameAmount('0.30', '0.3')).toBe(true);
    expect(sameAmount('-1.50', '-1.5')).toBe(true);
    expect(sameAmount('0.00', '-0')).toBe(true);
    // Beyond float precision: different amounts must not compare equal (FE-F33 review).
    expect(sameAmount('1234567890123456.78', '1234567890123456.79')).toBe(false);
    expect(sameAmount('120.00', '120.01')).toBe(false);
  });
});
