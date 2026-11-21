import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { nextIntent } from './intent';
import { holds } from './permissions';
import { fieldErrors } from './server-errors';

describe('nextIntent', () => {
  let n = 0;
  const newKey = () => `key-${(n += 1)}`;

  it('starts an intent with a new key', () => {
    expect(nextIntent(undefined, { a: 1 }, newKey)).toEqual({ body: { a: 1 }, idempotencyKey: 'key-1' });
  });

  it('keeps the key while the body is the same (a retry), and makes a new one for a corrected body', () => {
    const first = nextIntent(undefined, { a: 1 }, newKey);
    expect(nextIntent(first, { a: 1 }, newKey)).toBe(first);
    expect(nextIntent(first, { a: 2 }, newKey).idempotencyKey).not.toBe(first.idempotencyKey);
  });
});

describe('holds', () => {
  const store = 'store-1';

  it('holds a company-wide permission or one granted in this store, not in another', () => {
    expect(holds(['PERM_INVENTORY_MOVE'], 'INVENTORY_MOVE', store)).toBe(true);
    expect(holds(['PERM_INVENTORY_MOVE@store-1'], 'INVENTORY_MOVE', store)).toBe(true);
    expect(holds(['PERM_INVENTORY_MOVE@store-2'], 'INVENTORY_MOVE', store)).toBe(false);
    expect(holds(['PERM_INVENTORY_MOVE_X'], 'INVENTORY_MOVE', store)).toBe(false);
    expect(holds([], 'INVENTORY_MOVE', store)).toBe(false);
  });
});

describe('fieldErrors', () => {
  const invalid = (errors: { field: string; message: string }[]) =>
    new ApiError({ status: 400, code: 'VALIDATION_FAILED', title: 'Invalid', errors });

  it('puts line violations on the array field when the form has the array, else on the one-line field', () => {
    const error = invalid([
      { field: 'lines[1].quantity', message: 'q' },
      { field: 'number', message: 'n' },
      { field: 'other', message: 'o' },
    ]);
    expect(fieldErrors(error, ['number', 'lines'])).toEqual({
      fields: [
        ['lines.1.quantity', 'q'],
        ['number', 'n'],
      ],
      others: ['o'],
    });
    expect(fieldErrors(invalid([{ field: 'lines[0].unitCost', message: 'c' }]), ['unitCost'])).toEqual({
      fields: [['unitCost', 'c']],
      others: [],
    });
  });

  it('is nothing for an answer without field violations', () => {
    expect(fieldErrors(new ApiError({ status: 409, code: 'PO_STATE', title: 'x' }), ['number'])).toBeUndefined();
    expect(fieldErrors(invalid([]), ['number'])).toBeUndefined();
    expect(fieldErrors(new Error('x'), ['number'])).toBeUndefined();
  });
});
