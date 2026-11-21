import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { atDelivery, deliveriesEndpoint, openOrderEndpoint, zonesEndpoint } from '../../test/delivery';
import { catalogEndpoint, created, json, NEW_ORDER, NEW_ORDER_ID, orderEndpoint } from '../../test/order';

const TAKEAWAY_ORDER = { ...NEW_ORDER, mode: 'TAKEAWAY' as const, tableId: null, tableCode: null, guests: null };

async function openNewOrder(options?: Parameters<typeof atDelivery>[0]) {
  deliveriesEndpoint(json([]));
  zonesEndpoint();
  catalogEndpoint();
  const rendered = await atDelivery(options);
  await rendered.user.click(screen.getByRole('button', { name: 'Novo pedido' }));
  return { ...rendered, dialog: await screen.findByRole('dialog', { name: 'Novo pedido fora de sala' }) };
}

describe('a new takeaway or delivery order', () => {
  it('a takeaway opens at once and lands in its order to add the items', async () => {
    const opened = openOrderEndpoint(created(TAKEAWAY_ORDER));
    orderEndpoint(NEW_ORDER_ID, json(TAKEAWAY_ORDER));
    const { user, router, dialog } = await openNewOrder();

    await user.click(within(dialog).getByRole('radio', { name: /^Takeaway/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Abrir takeaway' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`));
    expect(opened).toHaveLength(1);
    expect(opened[0]!.body).toEqual({ mode: 'TAKEAWAY' });
    expect(opened[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a lost answer offers "Tentar novamente", which resends the same Idempotency-Key', async () => {
    const opened = openOrderEndpoint(() => HttpResponse.error(), created(TAKEAWAY_ORDER));
    orderEndpoint(NEW_ORDER_ID, json(TAKEAWAY_ORDER));
    const { user, router, dialog } = await openNewOrder();

    await user.click(within(dialog).getByRole('radio', { name: /^Takeaway/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Abrir takeaway' }));
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Sem ligação — a abertura do pedido não foi confirmada.');
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`));
    expect(opened[1]!.idempotencyKey).toBe(opened[0]!.idempotencyKey);
  });

  it('without the delivery permission only a takeaway is offered', async () => {
    const { dialog } = await openNewOrder({ permissions: [] });

    expect(within(dialog).queryByRole('radio', { name: /^Delivery/ })).not.toBeInTheDocument();
    expect(within(dialog).getByRole('radio', { name: /^Takeaway/ })).toBeChecked();
  });
});
