import { act, screen, within } from '@testing-library/react';
import { catalogEndpoint, json, ORDER, ORDER_ID, orderEndpoint } from './order';
import { renderPos } from './render-pos';
import { tableMap, tablesEndpoint, token, tokenEndpoint } from './server';
import { MAP } from './table-map';

/** The order screen of M2, its order answering `answers` in turn (the last repeats). */
export async function atOrder(
  answers: Parameters<typeof orderEndpoint>[1][] = [json(ORDER)],
  options: Parameters<typeof renderPos>[0] = {},
) {
  tokenEndpoint(token('jwt-1'));
  tablesEndpoint(tableMap(MAP));
  const catalog = catalogEndpoint();
  const order = orderEndpoint(ORDER_ID, ...answers);
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables', ...options });
  // The login lands on the map; from there, the order.
  await screen.findByRole('heading', { name: 'Mesas' });
  await act(() => rendered.router.navigate(`/orders/${ORDER_ID}`));
  return { ...rendered, catalog, order };
}

/** The order and the catalog both shown. */
export async function ready() {
  await screen.findByRole('heading', { name: 'Mesa M2' });
  await screen.findByRole('button', { name: /^Bica/ });
}

export const panel = () => screen.getByRole('complementary', { name: 'Pedido' });
export const drafts = () => within(panel()).getByRole('list', { name: 'Por enviar' });
