import { act, screen } from '@testing-library/react';
import { json, ORDER, ORDER_ID, orderEndpoint } from './order';
import { renderPos } from './render-pos';
import { SESSION, tableMap, tablesEndpoint, token, tokenEndpoint } from './server';
import { CASHIER_PERMISSIONS } from './settlement';
import { MAP } from './table-map';

/**
 * M2's "Conta & Pagamento" screen, for a cashier (or `permissions`): the login lands on the map, then the payment
 * route. The test stands in for the settlement endpoint (`serverSettlement`) before calling this.
 */
export async function atPayment({ permissions = CASHIER_PERMISSIONS }: { permissions?: string[] } = {}) {
  tokenEndpoint(token('jwt-1'));
  tablesEndpoint(tableMap(MAP));
  const order = orderEndpoint(ORDER_ID, json(ORDER));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  act(() => rendered.services.session.start({ ...SESSION, permissions }));
  await screen.findByRole('heading', { name: 'Mesas' });
  await act(() => rendered.router.navigate(`/orders/${ORDER_ID}/payment`));
  return { ...rendered, order };
}

/** The bill shown: its heading and the server's total. */
export async function billShown() {
  await screen.findByRole('heading', { name: 'Conta da mesa M2' });
}
