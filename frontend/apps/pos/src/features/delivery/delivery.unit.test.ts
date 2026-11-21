import { ApiError } from '@restaurantpro/api-client';
import { describe, expect, it } from 'vitest';
import {
  addressText,
  deliveryOrder,
  type DeliveryDraft,
  intentState,
  isChangedElsewhere,
  newCustomer,
  mayRunDeliveries,
  storeClock,
  toDeliveries,
} from './delivery';
import { refusal } from './messages';

const NOW = new Date('2026-11-18T18:00:00.000Z');
const COMPLETE: DeliveryDraft = {
  customerId: 'c1',
  address: { kind: 'saved', addressId: 'a1' },
  zoneId: 'z1',
  promiseMinutes: null,
  notes: '',
};
const blankAddress = { kind: 'new', line1: '', line2: '', postalCode: '', city: '' } as const;

const problem = (status: number, code: string) => new ApiError({ status, code, title: `Title ${code}` });

describe('deliveryOrder', () => {
  it('is the DELIVERY order of a complete draft with a saved address, without a promise or notes', () => {
    expect(deliveryOrder(COMPLETE, NOW)).toEqual({
      mode: 'DELIVERY',
      delivery: { customerId: 'c1', addressId: 'a1', zoneId: 'z1' },
    });
  });

  it('sends a typed address trimmed, with only the parts filled in', () => {
    const draft: DeliveryDraft = { ...COMPLETE, address: { ...blankAddress, line1: ' Rua A 1 ', city: ' Maputo ' } };
    expect(deliveryOrder(draft, NOW)?.delivery).toEqual({
      customerId: 'c1',
      zoneId: 'z1',
      address: { line1: 'Rua A 1', city: 'Maputo' },
    });
  });

  it('keeps every part of a typed address that has one', () => {
    const address = { kind: 'new', line1: 'Rua A 1', line2: '2.º', postalCode: '1100', city: 'Maputo' } as const;
    expect(deliveryOrder({ ...COMPLETE, address }, NOW)?.delivery?.address).toEqual({
      line1: 'Rua A 1',
      line2: '2.º',
      postalCode: '1100',
      city: 'Maputo',
    });
  });

  it('promises the minutes from now as an instant, and sends the notes trimmed', () => {
    expect(deliveryOrder({ ...COMPLETE, promiseMinutes: 30, notes: ' Tocar à campainha ' }, NOW)?.delivery).toEqual({
      customerId: 'c1',
      addressId: 'a1',
      zoneId: 'z1',
      promisedAt: '2026-11-18T18:30:00.000Z',
      notes: 'Tocar à campainha',
    });
  });

  it.each([
    ['no customer', { customerId: undefined }],
    ['no address', { address: undefined }],
    ['a typed address without its first line', { address: { ...blankAddress, line1: '  ', city: 'Maputo' } }],
    ['no zone', { zoneId: undefined }],
  ])('is nothing yet with %s', (_, change) => {
    expect(deliveryOrder({ ...COMPLETE, ...change } as DeliveryDraft, NOW)).toBeUndefined();
  });
});

describe('newCustomer', () => {
  it('needs a name, and sends the phone only when given, both trimmed', () => {
    expect(newCustomer(' Joana ', ' 847 ')).toEqual({ name: 'Joana', phone: '847' });
    expect(newCustomer('Joana', '  ')).toEqual({ name: 'Joana' });
    expect(newCustomer('  ', '847')).toBeUndefined();
  });
});

describe('intentState', () => {
  it('waits for "Tentar novamente" only when an intent is kept and nothing is in flight', () => {
    expect(intentState({ pending: false, intent: undefined })).toEqual({ awaitingRetry: false, locked: false });
    expect(intentState({ pending: true, intent: {} })).toEqual({ awaitingRetry: false, locked: true });
    expect(intentState({ pending: false, intent: {} })).toEqual({ awaitingRetry: true, locked: true });
    expect(intentState({ pending: true, intent: undefined })).toEqual({ awaitingRetry: false, locked: true });
  });
});

describe('toDeliveries', () => {
  const view = { orderId: 'o1', orderNumber: 7, status: 'PENDING', version: 0 } as const;

  it('keeps what the board shows, and the reason of the latest return', () => {
    const [delivery] = toDeliveries([
      {
        ...view,
        zoneCode: 'Z1',
        phone: '84',
        promisedAt: 'p',
        history: [
          { toStatus: 'RETURNED', reason: 'first' },
          { toStatus: 'OUT_FOR_DELIVERY' },
          { toStatus: 'RETURNED', reason: 'last' },
          { toStatus: 'PENDING', reason: 'not a return' },
        ],
      },
    ]);
    expect(delivery).toEqual({
      orderId: 'o1',
      orderNumber: 7,
      status: 'PENDING',
      version: 0,
      zoneCode: 'Z1',
      phone: '84',
      address: undefined,
      promisedAt: 'p',
      returnReason: 'last',
    });
  });

  it('gives an empty zone and no reason when the view has none', () => {
    expect(toDeliveries([view])[0]).toMatchObject({ zoneCode: '', returnReason: undefined });
  });

  it.each([['orderId'], ['orderNumber'], ['status'], ['version']])('drops a view without its %s', (field) => {
    expect(toDeliveries([{ ...view, [field]: undefined }])).toEqual([]);
  });

  it('keeps a delivery numbered 0 at version 0', () => {
    expect(toDeliveries([{ ...view, orderNumber: 0 }])).toHaveLength(1);
  });

  it('reads no answer as no delivery', () => {
    expect(toDeliveries(undefined)).toEqual([]);
  });
});

describe('addressText', () => {
  it('joins the parts the address has, the postal code before the city', () => {
    expect(addressText({ line1: 'Rua A 1', line2: '2.º', postalCode: '1100', city: 'Maputo' })).toBe('Rua A 1, 2.º, 1100 Maputo');
    expect(addressText({ line1: 'Rua A 1', city: 'Maputo' })).toBe('Rua A 1, Maputo');
    expect(addressText(undefined)).toBe('');
  });
});

describe('storeClock', () => {
  it('shows the instant on the store clock', () => {
    expect(storeClock('2026-11-18T18:15:00Z', 'Africa/Maputo')).toBe('20:15');
  });
});

describe('mayRunDeliveries', () => {
  it('needs the delivery permission in the session store', () => {
    expect(mayRunDeliveries({ storeId: 's1', permissions: ['PERM_DELIVERY_OPERATE@s1'] })).toBe(true);
    expect(mayRunDeliveries({ storeId: 's1', permissions: ['PERM_DELIVERY_OPERATE@s2'] })).toBe(false);
    expect(mayRunDeliveries(undefined)).toBe(false);
  });
});

describe('isChangedElsewhere', () => {
  it('is a 409 DELIVERY_STATE or a stale version, nothing else', () => {
    expect(isChangedElsewhere(problem(409, 'DELIVERY_STATE'))).toBe(true);
    expect(isChangedElsewhere(problem(409, 'VERSION_CONFLICT'))).toBe(true);
    expect(isChangedElsewhere(problem(422, 'DELIVERY_HAS_DRAFTS'))).toBe(false);
    expect(isChangedElsewhere(new Error('DELIVERY_STATE'))).toBe(false);
  });
});

describe('refusal', () => {
  it('says what was not confirmed without an answer', () => {
    expect(refusal(problem(0, 'NETWORK'), 'a expedição')).toBe('Sem ligação — a expedição não foi confirmada.');
  });

  it('names a known code in Portuguese, and falls back to the title', () => {
    expect(refusal(problem(422, 'ZONE_INACTIVE'), 'x')).toBe('A zona de entrega está inativa. Escolha outra.');
    expect(refusal(problem(422, 'SOMETHING_NEW'), 'x')).toBe('Title SOMETHING_NEW');
  });

  it('says an answer it cannot read is incomplete', () => {
    expect(refusal(new Error('boom'), 'a entrega')).toBe('Não foi possível confirmar a entrega. A resposta do servidor está incompleta.');
  });
});
