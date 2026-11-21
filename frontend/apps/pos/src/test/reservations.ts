import type { components } from '@restaurantpro/api-client';
import { act, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { expect, onTestFinished, vi } from 'vitest';
import { renderPos } from './render-pos';
import { recording, SESSION, tableMap, tablesEndpoint, token, tokenEndpoint } from './server';
import { id, MAP } from './table-map';

type Schemas = components['schemas'];
export type ReservationView = Schemas['com.xavier.backend.dining.model.dto.ReservationView'];
export type ReservationRequest = Schemas['com.xavier.backend.dining.model.dto.ReservationRequest'];
export type ReservationUpdateRequest = Schemas['com.xavier.backend.dining.model.dto.ReservationUpdateRequest'];
export type ReservationCancelRequest = Schemas['com.xavier.backend.dining.model.dto.ReservationCancelRequest'];
export type ReservationNoShowRequest = Schemas['com.xavier.backend.dining.model.dto.ReservationNoShowRequest'];
export type CustomerCandidate = Schemas['com.xavier.backend.customers.model.dto.CustomerCandidate'];
export type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];
export type CustomerDetails = Schemas['com.xavier.backend.customers.model.dto.CustomerDetails'];
type OpenTableRequest = Schemas['com.xavier.backend.orders.model.dto.OpenTableRequest'];
type TableMap = Schemas['com.xavier.backend.dining.model.dto.TableMap'];

type Answer<Body = undefined> = (body: Body) => Response | Promise<Response>;

/** Friday 2026-11-20, 12:00 in the store (Africa/Maputo, UTC+2): "today" for every reservation test. */
export const NOW = '2026-11-20T10:00:00Z';
export const TODAY = '2026-11-20';

export const RESERVATION = {
  booked: id(301),
  seated: id(302),
  noShow: id(303),
  cancelled: id(304),
};
export const CUSTOMER_ID = id(401);

const reservation = (n: string, fields: Partial<ReservationView>): ReservationView => ({
  id: n,
  storeId: SESSION.storeId,
  status: 'BOOKED',
  startsAt: '2026-11-20T17:30:00Z',
  durationMinutes: 120,
  partySize: 2,
  contactName: 'Sem nome',
  tableIds: [],
  version: 3,
  createdAt: '2026-11-19T09:00:00Z',
  ...fields,
});

/** The store's day, as the backend orders it (by start). Times are UTC: 17:30Z shows as 19:30 in the store. */
export const DAY: ReservationView[] = [
  reservation(RESERVATION.noShow, {
    status: 'NO_SHOW',
    startsAt: '2026-11-20T09:00:00Z',
    partySize: 2,
    contactName: 'Pedro Miranda',
    contactPhoneDigits: '841110000',
    tableIds: [id(10)],
    noShowAt: '2026-11-20T09:30:00Z',
  }),
  reservation(RESERVATION.seated, {
    status: 'SEATED',
    startsAt: '2026-11-20T09:30:00Z',
    partySize: 3,
    contactName: 'Família Costa',
    tableIds: [id(2)],
    seatedAt: '2026-11-20T09:35:00Z',
    occupationId: id(450),
  }),
  reservation(RESERVATION.booked, {
    status: 'BOOKED',
    startsAt: '2026-11-20T17:30:00Z',
    partySize: 4,
    contactName: 'Ana Ferreira',
    contactPhoneDigits: '841234567',
    customerId: CUSTOMER_ID,
    notes: 'Cadeira de bebé',
    tableIds: [id(1), id(10)],
  }),
  reservation(RESERVATION.cancelled, {
    status: 'CANCELLED',
    startsAt: '2026-11-20T18:00:00Z',
    partySize: 5,
    contactName: 'Carla Lopes',
    cancelReason: 'O cliente telefonou',
    cancelledAt: '2026-11-20T08:00:00Z',
  }),
];

export const BOOKED = DAY[2]!;

export const CANDIDATE: CustomerCandidate = {
  id: CUSTOMER_ID,
  name: 'Ana Ferreira',
  phones: ['841234567'],
  taxId: '123456789',
  active: true,
};

export const json = (body: unknown) => () => HttpResponse.json(body as object);

const answering =
  <Body>(answers: Answer<Body>[]) =>
  (body: Body) =>
    (answers.length > 1 ? answers.shift()! : answers[0]!)(body);

/** `GET /api/v1/pos/reservations?date=`, answering from a queue whose last answer repeats. */
export const reservationsEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/reservations', answering(answers.length ? answers : [json(DAY)]));

/** `POST /api/v1/pos/reservations`. */
export const bookEndpoint = (...answers: Answer<ReservationRequest>[]) =>
  recording('post', '/api/v1/pos/reservations', answering(answers));

/** `PUT /api/v1/pos/reservations/{id}`. */
export const updateEndpoint = (reservationId: string, ...answers: Answer<ReservationUpdateRequest>[]) =>
  recording('put', `/api/v1/pos/reservations/${reservationId}`, answering(answers));

/** `POST /api/v1/pos/reservations/{id}/cancel`. */
export const cancelEndpoint = (reservationId: string, ...answers: Answer<ReservationCancelRequest>[]) =>
  recording('post', `/api/v1/pos/reservations/${reservationId}/cancel`, answering(answers));

/** `POST /api/v1/pos/reservations/{id}/no-show`. */
export const noShowEndpoint = (reservationId: string, ...answers: Answer<ReservationNoShowRequest>[]) =>
  recording('post', `/api/v1/pos/reservations/${reservationId}/no-show`, answering(answers));

/** `POST /api/v1/pos/tables/{tableId}/open` with a reservation: seating it. */
export const seatEndpoint = (tableId: string, ...answers: Answer<OpenTableRequest>[]) =>
  recording('post', `/api/v1/pos/tables/${tableId}/open`, answering(answers));

/** `GET /api/v1/pos/customers?q=`. */
export const customersEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/customers', answering(answers));

/** `GET /api/v1/pos/customers/{id}`. */
export const customerEndpoint = (customerId: string, ...answers: Answer[]) =>
  recording<undefined>('get', `/api/v1/pos/customers/${customerId}`, answering(answers));

export const ANA_DETAILS: CustomerDetails = {
  id: CUSTOMER_ID,
  name: 'Ana Ferreira',
  active: true,
  phones: [],
  addresses: [],
  version: 1,
};

/** `POST /api/v1/pos/customers`. */
export const createCustomerEndpoint = (...answers: Answer<CustomerCreateRequest>[]) =>
  recording('post', '/api/v1/pos/customers', answering(answers));

/** The waiter may manage reservations and customers; `permissions` replaces that. */
export const RESERVATION_PERMISSIONS = [
  `PERM_RESERVATION_VIEW@${SESSION.storeId}`,
  `PERM_RESERVATION_MANAGE@${SESSION.storeId}`,
  `PERM_CUSTOMER_VIEW@${SESSION.storeId}`,
  `PERM_CUSTOMER_MANAGE@${SESSION.storeId}`,
  `PERM_DINING_OPEN@${SESSION.storeId}`,
];

/**
 * The reservations screen at NOW, the store's table map behind it, logged in with `permissions`. Only `Date` is
 * fixed: timers stay real, so MSW and user events run as usual.
 */
export async function atReservations({
  day = [json(DAY)] as Answer[],
  permissions = RESERVATION_PERMISSIONS,
  map = MAP,
}: { day?: Answer[]; permissions?: string[]; map?: TableMap } = {}) {
  vi.setSystemTime(new Date(NOW));
  onTestFinished(() => void vi.useRealTimers());
  tokenEndpoint(token('jwt-1'));
  const tables = tablesEndpoint(tableMap(map));
  const days = reservationsEndpoint(...day);
  const rendered = await renderPos({ device: 'enrolled', path: '/login' });
  await waitFor(() => expect(rendered.services.tokens.status()).toBe('ready'));
  act(() => rendered.services.session.start({ ...SESSION, permissions }));
  await act(() => rendered.router.navigate('/reservations'));
  return { ...rendered, days, tables };
}

/** The reservation's row in the day list. */
export const row = (name: string) => screen.findByRole('article', { name: new RegExp(name) });
