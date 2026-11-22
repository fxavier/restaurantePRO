import type { components } from '@restaurantpro/api-client';
import { act, screen } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { queue, recording, type Seen, SESSION, token, tokenEndpoint } from './server';
import { renderPos } from './render-pos';
import { id } from './table-map';

type Schemas = components['schemas'];
export type TicketView = Schemas['com.xavier.backend.production.model.dto.TicketView'];
export type KitchenCommandRequest = Schemas['com.xavier.backend.production.model.dto.KitchenCommandRequest'];

/** Ana may watch the kitchen display, and move its rounds. */
export const VIEWER = `PERM_PRODUCTION_VIEW@${SESSION.storeId}`;
export const OPERATOR = `PERM_PRODUCTION_OPERATE@${SESSION.storeId}`;

const RULE = '-'.repeat(42);

/** A round's printed lines, as the backend lays them out (TicketLayouts.batch) and answers them as plain text. */
const printed = (destination: string, header: string, round: number, ...items: string[]) => [
  destination,
  header,
  `Ronda ${round}`,
  '22/11/2026 12:00',
  RULE,
  ...items,
];

const ticket = (n: number, fields: Partial<TicketView> & Pick<TicketView, 'lines'>): TicketView => ({
  id: id(n),
  storeId: SESSION.storeId,
  sourceType: 'BATCH',
  sourceId: id(n + 100),
  destination: 'KITCHEN',
  kind: 'ORIGINAL',
  orderId: id(n + 200),
  routing: 'ROUTED',
  suppressed: false,
  createdAt: '2026-11-22T10:00:00Z',
  ...fields,
});

const progress = (status: NonNullable<TicketView['kitchen']>['status'], queuedAt: string, version = 0) => ({
  status,
  version,
  queuedAt,
});

/** Table 4's second round waits, table 8's is being cooked, the bar's round for table 3 is ready; 05 left earlier. */
export const TICKET = {
  queued: ticket(2001, {
    lines: printed('KITCHEN', 'Mesa 04 - Pedido #4092', 2, '2 x Bife da Vazia', '   > mal passado', '1 x Polvo à Lagareiro', '   + 1 x Batata a murro'),
    kitchen: progress('QUEUED', '2026-11-22T09:36:00Z'),
  }),
  cooking: ticket(2002, {
    lines: printed('KITCHEN', 'Mesa 08 - Pedido #4105', 1, '1 x Costeletão Maturado'),
    kitchen: progress('IN_PREPARATION', '2026-11-22T09:49:00Z', 1),
  }),
  ready: ticket(2003, {
    destination: 'BAR',
    lines: printed('BAR', 'Mesa 03 - Pedido #4112', 1, '2 x Fino 30cl'),
    kitchen: progress('READY', '2026-11-22T09:54:00Z', 2),
  }),
  bumped: ticket(2004, {
    lines: printed('KITCHEN', 'Mesa 05 - Pedido #4098', 3, '2 x Petit Gâteau'),
    kitchen: progress('BUMPED', '2026-11-22T09:20:00Z', 3),
  }),
};

/** The page `GET /kds` answers. */
export const page = (...tickets: TicketView[]) => () =>
  HttpResponse.json({ content: tickets, page: 0, size: 50, totalElements: tickets.length });

type Answer<Body = undefined> = (body: Body, seen: Seen<Body>) => Response | Promise<Response>;

/** `GET /api/v1/pos/production-tickets/kds`. */
export const kdsEndpoint = (...answers: Answer[]) =>
  recording<undefined>('get', '/api/v1/pos/production-tickets/kds', queue(...answers));

/** The answer for each station asked (`?destination=`), every station's rounds when none is. */
export const byStation =
  (stations: Record<string, TicketView[]>, all: TicketView[]): Answer =>
  (_, seen) => {
    const destination = new URL(seen.url).searchParams.get('destination');
    return page(...(destination ? (stations[destination] ?? []) : all))();
  };

/** `POST /api/v1/pos/production-tickets/{id}/start|ready|bump|recall`. */
export const kitchenCommandEndpoint = (
  ticketId: string,
  command: 'start' | 'ready' | 'bump' | 'recall',
  ...answers: Answer<KitchenCommandRequest>[]
) => recording<KitchenCommandRequest>('post', `/api/v1/pos/production-tickets/${ticketId}/${command}`, queue(...answers));

/** The POS at `/kds`, Ana logged in with `permissions` (by default she may watch and move rounds). */
export async function atKitchen({ permissions = [...SESSION.permissions, VIEWER, OPERATOR] }: { permissions?: string[] } = {}) {
  tokenEndpoint(token('jwt-1'));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  act(() => rendered.services.session.start({ ...SESSION, permissions }));
  // The login lands on the table map; the section bar leads on, as a cook goes.
  await rendered.user.click(await screen.findByRole('link', { name: 'KDS' }));
  await screen.findByRole('heading', { name: 'Ecrã de cozinha' });
  return rendered;
}
