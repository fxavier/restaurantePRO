import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import { dataState } from './data-state';
import { nextIntent } from './intent';
import {
  addressKindLabel,
  formatBusinessDate,
  formatPoints,
  formatPointsChange,
  ledgerKindLabel,
  loyaltyKindLabel,
} from './labels';
import { allowedFrom } from './permissions';

describe('nextIntent', () => {
  const keys = ['k1', 'k2'];
  const newKey = () => keys.shift()!;

  it('keeps the key while the request is the same, and takes a new one for another request', () => {
    const first = nextIntent(undefined, { reason: 'a' }, newKey);
    const again = nextIntent(first, { reason: 'a' }, newKey);
    const other = nextIntent(again, { reason: 'b' }, newKey);

    expect(first).toEqual({ body: { reason: 'a' }, key: 'k1' });
    expect(again).toBe(first);
    expect(other).toEqual({ body: { reason: 'b' }, key: 'k2' });
  });
});

describe('labels', () => {
  it('shows a business day as the store reads it, and anything else unchanged', () => {
    expect(formatBusinessDate('2026-11-02')).toBe('02/11/2026');
    expect(formatBusinessDate('2026-11-02T10:00:00Z')).toBe('2026-11-02T10:00:00Z');
    expect(formatBusinessDate(undefined)).toBe('');
    expect(formatBusinessDate('')).toBe('');
  });

  it('names the kinds, and shows an unknown one as sent', () => {
    expect(ledgerKindLabel('DEBIT')).toBe('Débito');
    expect(ledgerKindLabel('CREDIT')).toBe('Crédito');
    expect(ledgerKindLabel('OTHER')).toBe('OTHER');
    expect(ledgerKindLabel(undefined)).toBe('');
    expect(loyaltyKindLabel('REDEEM_REVERSAL')).toBe('Anulação de resgate');
    expect(loyaltyKindLabel('NEW')).toBe('NEW');
    expect(loyaltyKindLabel(undefined)).toBe('');
    expect(addressKindLabel('DELIVERY')).toBe('Entrega');
    expect(addressKindLabel('BILLING')).toBe('Faturação');
    expect(addressKindLabel('X')).toBe('X');
    expect(addressKindLabel(undefined)).toBe('');
  });

  it('signs a movement of points and not a balance', () => {
    expect(formatPointsChange(300)).toBe('+300');
    expect(formatPointsChange(-20)).toMatch(/^[-−]20$/);
    expect(formatPointsChange(0)).toBe('0');
    expect(formatPointsChange(undefined)).toBe('');
    expect(formatPoints(320)).toBe('320');
    expect(formatPoints(undefined)).toBe('');
  });
});

describe('allowedFrom', () => {
  it('grants a permission held company-wide or in any store, and nothing else', () => {
    const allowed = allowedFrom(['PERM_CUSTOMER_MANAGE', 'PERM_LOYALTY_ADJUST@store-1', 'PERM_CREDIT_LIMIT_MANAGEX']);

    expect(allowed).toEqual({
      CUSTOMER_MANAGE: true,
      CREDIT_LIMIT_MANAGE: false,
      LOYALTY_VIEW: false,
      LOYALTY_ENROLL: false,
      LOYALTY_ADJUST: true,
    });
  });
});

describe('dataState', () => {
  const base = { isPending: false, isError: false, error: null, data: [1] };

  it('reads a query as loading, empty, ready, error or unavailable', () => {
    expect(dataState({ ...base, isPending: true, data: undefined })).toBe('loading');
    expect(dataState({ ...base, data: undefined })).toBe('loading');
    expect(dataState(base)).toBe('ready');
    expect(dataState(base, (d) => d.length === 0)).toBe('ready');
    expect(dataState({ ...base, data: [] }, (d) => d.length === 0)).toBe('empty');
    expect(dataState({ ...base, isError: true, error: new ApiError({ status: 500, code: 'X', title: 'x' }) })).toBe('error');
    expect(dataState({ ...base, isError: true, error: new ApiError({ status: 0, code: 'NETWORK', title: 'x' }) })).toBe(
      'unavailable',
    );
  });
});
