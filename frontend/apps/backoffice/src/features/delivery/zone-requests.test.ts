import { describe, expect, it } from 'vitest';
import { intentFor, toCreateRequest, toUpdateRequest, zoneValues } from './zone-requests';

const STORE = '11111111-0000-4000-8000-000000000001';
const zone = {
  id: 'z',
  storeId: STORE,
  code: 'Z1',
  name: 'Centro',
  feeItemId: 'fee',
  feeVariantId: 'night',
  active: false,
  version: 3,
};

describe('zoneValues', () => {
  it('starts a new zone empty and active', () => {
    expect(zoneValues()).toEqual({ code: '', name: '', feeItemId: '', feeVariantId: '', active: true });
  });

  it("holds an existing zone's values", () => {
    expect(zoneValues(zone)).toEqual({ code: 'Z1', name: 'Centro', feeItemId: 'fee', feeVariantId: 'night', active: false });
    expect(zoneValues({ ...zone, feeVariantId: undefined, active: true }).feeVariantId).toBe('');
  });
});

describe('requests', () => {
  const values = { code: 'Z1', name: 'Centro', feeItemId: 'fee', feeVariantId: '', active: false };

  it('sends a variant only when one is chosen', () => {
    expect(toCreateRequest(STORE, values)).toEqual({ storeId: STORE, code: 'Z1', name: 'Centro', feeItemId: 'fee' });
    expect(toCreateRequest(STORE, { ...values, feeVariantId: 'night' })).toEqual({
      storeId: STORE,
      code: 'Z1',
      name: 'Centro',
      feeItemId: 'fee',
      feeVariantId: 'night',
    });
  });

  it('updates on the version read, without the code', () => {
    expect(toUpdateRequest(values, 3)).toEqual({ name: 'Centro', feeItemId: 'fee', active: false, version: 3 });
    expect(toUpdateRequest({ ...values, feeVariantId: 'night', active: true }, 0)).toEqual({
      name: 'Centro',
      feeItemId: 'fee',
      feeVariantId: 'night',
      active: true,
      version: 0,
    });
  });
});

describe('intentFor', () => {
  let n = 0;
  const newKey = () => `key-${++n}`;

  it('makes a key for the first request, keeps it for the same request, and makes another for a corrected one', () => {
    const first = intentFor(undefined, { a: 1 }, newKey);
    expect(first).toEqual({ body: { a: 1 }, key: 'key-1' });
    expect(intentFor(first, { a: 1 }, newKey)).toBe(first);
    expect(intentFor(first, { a: 2 }, newKey)).toEqual({ body: { a: 2 }, key: 'key-2' });
  });
});
