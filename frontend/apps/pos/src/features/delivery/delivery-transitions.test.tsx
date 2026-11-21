import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  atDelivery,
  DELIVERY,
  deliveredEndpoint,
  deliveriesEndpoint,
  dispatchEndpoint,
  returnedEndpoint,
} from '../../test/delivery';
import { json } from '../../test/order';
import { notificationsEndpoint, problem, SESSION } from '../../test/server';

const card = (orderNumber: number) => screen.findByRole('article', { name: `Entrega n.º ${orderNumber}` });
const OUT_NOW = { ...DELIVERY.pending, status: 'OUT_FOR_DELIVERY' as const, version: 1 };

describe('moving a delivery along', () => {
  it('"Expedir" sends a waiting delivery out with the logged-in employee as its courier, then reads the board again', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, json(OUT_NOW));
    const { user } = await atDelivery();

    const expedir = within(await card(82)).getByRole('button', { name: 'Expedir' });
    expect(expedir).toHaveAccessibleDescription('Estafeta: Ana Silva');
    await user.click(expedir);

    const onTheRoad = await screen.findByRole('region', { name: /^Em entrega/ });
    expect(await within(onTheRoad).findByRole('article', { name: 'Entrega n.º 82' })).toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(sent[0]!.body).toEqual({ courierEmployeeId: SESSION.employeeId, version: 0 });
    expect(sent[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(sent[0]!.posSession).toBe('pos-session-ana');
  });

  it('"Entregue" closes the trip of a delivery on the road, at its version', async () => {
    deliveriesEndpoint(json([DELIVERY.out]), json([]));
    const sent = deliveredEndpoint(DELIVERY.out.orderId!, json({ ...DELIVERY.out, status: 'DELIVERED', version: 2 }));
    const { user } = await atDelivery();

    await user.click(within(await card(85)).getByRole('button', { name: 'Entregue' }));

    expect(await screen.findByText('Nenhuma entrega em curso.')).toBeInTheDocument();
    expect(sent[0]!.body).toEqual({ version: 1 });
    expect(sent[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('"Devolvida" asks why, and sends the reason with the version', async () => {
    const back = { ...DELIVERY.out, status: 'RETURNED' as const, version: 2 };
    deliveriesEndpoint(json([DELIVERY.out]), json([back]));
    const sent = returnedEndpoint(DELIVERY.out.orderId!, json(back));
    const { user } = await atDelivery();

    await user.click(within(await card(85)).getByRole('button', { name: 'Devolvida' }));
    const dialog = await screen.findByRole('dialog', { name: 'Devolver a entrega n.º 85' });
    const confirm = within(dialog).getByRole('button', { name: 'Registar devolução' });
    expect(confirm).toBeDisabled();
    await user.type(within(dialog).getByLabelText('Motivo'), '  Morada errada ');
    await user.click(confirm);

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(within(await screen.findByRole('region', { name: /^Devolvidas/ })).getByRole('article')).toBeInTheDocument();
    expect(sent[0]!.body).toEqual({ reason: 'Morada errada', version: 1 });
  });

  it('a returned delivery can go out again', async () => {
    deliveriesEndpoint(json([DELIVERY.returned]));
    await atDelivery();

    expect(within(await card(79)).getByRole('button', { name: 'Expedir' })).toBeEnabled();
  });

  it('a delivery changed on another terminal (409) is refused, said so, and the board read again', async () => {
    const reads = deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    dispatchEndpoint(DELIVERY.pending.orderId!, () => problem(409, 'DELIVERY_STATE'));
    const { user } = await atDelivery();

    await user.click(within(await card(82)).getByRole('button', { name: 'Expedir' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A entrega foi alterada noutro terminal. O quadro foi atualizado; reveja e tente de novo.',
    );
    await waitFor(() => expect(reads.length).toBeGreaterThanOrEqual(2));
  });

  it('a refused dispatch names the reason (drafts still to send)', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    dispatchEndpoint(DELIVERY.pending.orderId!, () => problem(422, 'DELIVERY_HAS_DRAFTS'));
    const { user } = await atDelivery();

    await user.click(within(await card(82)).getByRole('button', { name: 'Expedir' }));

    expect(await within(await card(82)).findByRole('alert')).toHaveTextContent(
      'O pedido tem artigos por enviar. Envie-os ou apague-os antes de expedir.',
    );
  });

  it('a lost answer offers "Tentar novamente", which resends the same Idempotency-Key', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, () => HttpResponse.error(), json(OUT_NOW));
    const { user } = await atDelivery();

    await user.click(within(await card(82)).getByRole('button', { name: 'Expedir' }));
    const retry = await within(await card(82)).findByRole('button', { name: 'Tentar novamente' });
    expect(within(await card(82)).getByRole('alert')).toHaveTextContent('Sem ligação — a expedição não foi confirmada.');
    await user.click(retry);

    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]!.idempotencyKey).toBe(sent[0]!.idempotencyKey);
  });

  it('without the delivery permission, the board shows no command', async () => {
    deliveriesEndpoint(json([DELIVERY.pending, DELIVERY.out]));
    await atDelivery({ permissions: SESSION.permissions });

    await card(82);
    expect(screen.queryByRole('button', { name: 'Expedir' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Entregue' })).not.toBeInTheDocument();
  });

  it('"Sem ligação" disables every command', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    deliveriesEndpoint(json([DELIVERY.pending]));
    await atDelivery();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));

    expect(within(await card(82)).getByRole('button', { name: 'Expedir' })).toBeDisabled();
  });
});
