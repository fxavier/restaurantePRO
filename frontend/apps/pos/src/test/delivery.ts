import type { components } from '@restaurantpro/api-client';
import { act, screen } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { queue, recording, SESSION, token, tokenEndpoint } from './server';
import { renderPos } from './render-pos';
import { id } from './table-map';

type Schemas = components['schemas'];
export type DeliveryView = Schemas['com.xavier.backend.orders.delivery.model.dto.DeliveryView'];
export type ZoneView = Schemas['com.xavier.backend.orders.delivery.model.dto.ZoneView'];
export type CustomerCandidate = Schemas['com.xavier.backend.customers.model.dto.CustomerCandidate'];
export type CustomerDetails = Schemas['com.xavier.backend.customers.model.dto.CustomerDetails'];
export type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];
export type OpenOrderRequest = Schemas['com.xavier.backend.orders.model.dto.OpenOrderRequest'];
export type DispatchRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.DispatchRequest'];
export type DeliveredRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.DeliveredRequest'];
export type ReturnedRequest = Schemas['com.xavier.backend.orders.delivery.model.dto.ReturnedRequest'];

/** Ana may run deliveries in the terminal's store. */
export const OPERATOR = `PERM_DELIVERY_OPERATE@${SESSION.storeId}`;

export const ZONE = {
  foz: { id: id(1001), storeId: SESSION.storeId, code: 'Z1', name: 'Polana', active: true, feeItemId: id(707), version: 0 },
  sommerschield: {
    id: id(1002),
    storeId: SESSION.storeId,
    code: 'Z2',
    name: 'Sommerschield',
    active: true,
    feeItemId: id(707),
    version: 0,
  },
} satisfies Record<string, ZoneView>;

const delivery = (n: number, fields: Partial<DeliveryView> = {}): DeliveryView => ({
  orderId: id(n),
  orderNumber: n - 1000,
  storeId: SESSION.storeId,
  customerId: id(1201),
  status: 'PENDING',
  version: 0,
  zoneId: ZONE.foz.id,
  zoneCode: 'Z1',
  phone: '841234567',
  address: { line1: 'Av. Julius Nyerere 1200', line2: '3.º andar', city: 'Maputo' },
  // 18:15 UTC is 20:15 in Maputo (the store) and 15:15 in São Paulo (the tests' terminal).
  promisedAt: '2026-11-18T18:15:00Z',
  history: [],
  ...fields,
});

/** Order 82 waits for its courier, 85 is on the road, 79 came back. */
export const DELIVERY = {
  pending: delivery(1082),
  out: delivery(1085, {
    status: 'OUT_FOR_DELIVERY',
    version: 1,
    zoneId: ZONE.sommerschield.id,
    zoneCode: 'Z2',
    address: { line1: 'Rua da Mesquita 45' },
    promisedAt: undefined,
    dispatchedAt: '2026-11-18T17:50:00Z',
    courierEmployeeId: SESSION.employeeId,
  }),
  returned: delivery(1079, {
    status: 'RETURNED',
    version: 2,
    history: [
      { id: id(1301), fromStatus: 'PENDING', toStatus: 'OUT_FOR_DELIVERY', at: '2026-11-18T17:00:00Z' },
      { id: id(1302), fromStatus: 'OUT_FOR_DELIVERY', toStatus: 'RETURNED', at: '2026-11-18T17:30:00Z', reason: 'Cliente ausente' },
    ],
  }),
};

export const CUSTOMER = {
  marta: {
    id: id(1201),
    name: 'Marta Fontes',
    active: true,
    version: 0,
    phones: [{ id: id(1211), digits: '841234567', primary: true }],
    addresses: [
      { id: id(1221), line1: 'Av. Julius Nyerere 1200', line2: '3.º andar', city: 'Maputo', version: 0 },
      { id: id(1222), line1: 'Rua do Bagamoyo 10', city: 'Maputo', version: 0 },
    ],
  },
  nova: { id: id(1202), name: 'Joana Nova', active: true, version: 0, phones: [], addresses: [] },
} satisfies Record<string, CustomerDetails>;

export const candidateOf = (customer: CustomerDetails): CustomerCandidate => ({
  id: customer.id,
  name: customer.name,
  active: customer.active,
  phones: customer.phones?.map((phone) => phone.digits ?? '') ?? [],
});

type Answer<Body = undefined> = (body: Body) => Response | Promise<Response>;

/** `GET /api/v1/pos/deliveries`: the board. */
export const deliveriesEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/deliveries', queue(...answers));

/** `GET /api/v1/pos/delivery-zones`. */
export const zonesEndpoint = (...answers: Answer[]) =>
  recording<undefined>(
    'get',
    '/api/v1/pos/delivery-zones',
    queue(...(answers.length ? answers : [() => HttpResponse.json(Object.values(ZONE))])),
  );

/** `GET /api/v1/pos/customers?q=`. */
export const customerSearchEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/customers', queue(...answers));

/** `GET /api/v1/pos/customers/{id}`. */
export const customerEndpoint = (customerId: string, ...answers: Answer[]) =>
  recording<undefined>('get', `/api/v1/pos/customers/${customerId}`, queue(...answers));

/** `POST /api/v1/pos/customers`. */
export const createCustomerEndpoint = (...answers: Answer<CustomerCreateRequest>[]) =>
  recording<CustomerCreateRequest>('post', '/api/v1/pos/customers', queue(...answers));

/** `POST /api/v1/pos/orders`: a TAKEAWAY or DELIVERY order. */
export const openOrderEndpoint = (...answers: Answer<OpenOrderRequest>[]) =>
  recording<OpenOrderRequest>('post', '/api/v1/pos/orders', queue(...answers));

/** `POST /api/v1/pos/deliveries/{orderId}/dispatch`. */
export const dispatchEndpoint = (orderId: string, ...answers: Answer<DispatchRequest>[]) =>
  recording<DispatchRequest>('post', `/api/v1/pos/deliveries/${orderId}/dispatch`, queue(...answers));

/** `POST /api/v1/pos/deliveries/{orderId}/delivered`. */
export const deliveredEndpoint = (orderId: string, ...answers: Answer<DeliveredRequest>[]) =>
  recording<DeliveredRequest>('post', `/api/v1/pos/deliveries/${orderId}/delivered`, queue(...answers));

/** `POST /api/v1/pos/deliveries/{orderId}/returned`. */
export const returnedEndpoint = (orderId: string, ...answers: Answer<ReturnedRequest>[]) =>
  recording<ReturnedRequest>('post', `/api/v1/pos/deliveries/${orderId}/returned`, queue(...answers));

/** The POS at `/delivery`, Ana logged in with `permissions` (by default she may run deliveries). */
export async function atDelivery({ permissions = [...SESSION.permissions, OPERATOR] }: { permissions?: string[] } = {}) {
  tokenEndpoint(token('jwt-1'));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  act(() => rendered.services.session.start({ ...SESSION, permissions }));
  // The login lands on the table map; the section bar leads on, as a waiter goes.
  await rendered.user.click(await screen.findByRole('link', { name: 'Takeaway & Delivery' }));
  await screen.findByRole('heading', { name: 'Takeaway & Delivery' });
  return rendered;
}
