import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ORDER_ID } from '../../test/order';
import { atOrder, ready } from '../../test/order-screen';
import { renderPos } from '../../test/render-pos';
import { tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { serverSettlement } from '../../test/settlement';
import { MAP } from '../../test/table-map';
import { catalogEndpoint, json, ORDER, orderEndpoint } from '../../test/order';

describe('reaching the bill', () => {
  it('from the order screen, beside "Pedir"', async () => {
    serverSettlement();
    const { user, router } = await atOrder();
    await ready();

    await user.click(within(screen.getByRole('complementary', { name: 'Pedido' })).getByRole('link', { name: 'Conta & Pagamento' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}/payment`));
    expect(await screen.findByRole('heading', { name: 'Conta da mesa M2' })).toBeInTheDocument();
  });

  it('from an occupied table on the map, "Subtotal" (and "Dividir"); a free table has neither', async () => {
    serverSettlement();
    tokenEndpoint(token('jwt-1'));
    tablesEndpoint(tableMap(MAP));
    catalogEndpoint();
    orderEndpoint(ORDER_ID, json(ORDER));
    const { user, router } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
    const panel = () => screen.getByRole('complementary', { name: 'Detalhe da mesa' });

    await user.click(await screen.findByRole('button', { name: /^Mesa M1 / }));
    expect(within(panel()).queryByRole('link', { name: 'Subtotal' })).not.toBeInTheDocument();
    expect(within(panel()).queryByRole('link', { name: 'Dividir' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /^Mesa M2 / }));
    await user.click(await within(panel()).findByRole('link', { name: 'Subtotal' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}/payment`));
  });
});
