import { describe, expect, it } from 'vitest';
import { BATCH_ID, LINE, ORDER, withLines } from '../../test/order';
import { quantityLabel, toOrder } from './order';

describe('toOrder', () => {
  it('splits drafts from the rounds, each round with its confirmed lines, by round number', () => {
    const order = toOrder(ORDER);

    expect(order.drafts.map((line) => line.id)).toEqual([LINE.water.id, LINE.coffee.id]);
    expect(order.rounds).toEqual([expect.objectContaining({ id: BATCH_ID, batchNo: 1, lines: [expect.objectContaining({ id: LINE.steak.id })] })]);
    expect(order.unbatched).toEqual([]);
    expect(order.due).toEqual({ amount: 1350, currency: 'MZN' });
  });

  it('a confirmed line shows its net quantity, and one outside any round is kept apart', () => {
    const moved = { ...LINE.steak, id: 'moved', batchId: undefined, quantity: 2, netQuantity: 1 };
    const order = toOrder(withLines(ORDER, [moved]));

    expect(order.unbatched).toEqual([expect.objectContaining({ id: 'moved', quantity: 1 })]);
  });

  it('an answer without its lines, or a line without its version, is an error', () => {
    expect(() => toOrder({ ...ORDER, lines: undefined })).toThrow('Incomplete order');
    expect(() => toOrder(withLines(ORDER, [{ ...LINE.coffee, version: undefined }]))).toThrow('Incomplete order');
  });

  it('formats a quantity in the store locale', () => {
    expect(quantityLabel(2)).toBe('2');
    expect(quantityLabel(0.5)).toBe('0,5');
  });
});
