import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { COURIER, couriersEndpoint } from '../../test/couriers';
import { atDelivery, DELIVERY, deliveriesEndpoint, dispatchEndpoint } from '../../test/delivery';
import { json } from '../../test/order';
import { problem, SESSION } from '../../test/server';

const card = (orderNumber: number) => screen.findByRole('article', { name: `Entrega n.º ${orderNumber}` });
const OUT_NOW = { ...DELIVERY.pending, status: 'OUT_FOR_DELIVERY' as const, version: 1 };

/** The board with order 82 waiting, "Expedir" tapped: its dialog. */
async function dispatching() {
  const rendered = await atDelivery();
  await rendered.user.click(within(await card(82)).getByRole('button', { name: 'Expedir' }));
  const dialog = await screen.findByRole('dialog', { name: 'Expedir a entrega n.º 82' });
  return { ...rendered, dialog };
}

/** Picks Rita and confirms. */
async function sendRita({ user, dialog }: Awaited<ReturnType<typeof dispatching>>) {
  await user.click(await within(dialog).findByRole('radio', { name: /^Rita Mabunda/ }));
  await user.click(within(dialog).getByRole('button', { name: 'Expedir' }));
}

describe('dispatching a delivery with a courier', () => {
  it('"Expedir" asks which courier takes it, and sends that courier at the version the board showed', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    couriersEndpoint();
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, json(OUT_NOW));
    const { user } = await atDelivery();

    await user.click(within(await card(82)).getByRole('button', { name: 'Expedir' }));
    const dialog = await screen.findByRole('dialog', { name: 'Expedir a entrega n.º 82' });
    const confirm = within(dialog).getByRole('button', { name: 'Expedir' });
    expect(confirm).toBeDisabled();
    await user.click(await within(dialog).findByRole('radio', { name: /^Rita Mabunda/ }));
    await user.click(confirm);

    const onTheRoad = await screen.findByRole('region', { name: /^Em entrega/ });
    expect(await within(onTheRoad).findByRole('article', { name: 'Entrega n.º 82' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(sent).toHaveLength(1);
    expect(sent[0]!.body).toEqual({ courierEmployeeId: COURIER.rita.id, version: 0 });
    expect(sent[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    // The terminal's POS session rides on the command (D182).
    expect(sent[0]!.posSession).toBe('pos-session-ana');
  });

  it('the logged-in employee, when a courier of the store, is picked already and can be sent at once', async () => {
    const ana = { id: SESSION.employeeId, code: 'E01', name: 'Ana Silva' };
    deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    couriersEndpoint(json([COURIER.joao, ana, COURIER.rita]));
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, json(OUT_NOW));
    const { user, dialog } = await dispatching();

    expect(await within(dialog).findByRole('radio', { name: 'Ana Silva' })).toBeChecked();
    expect(within(dialog).getByRole('radio', { name: 'Rita Mabunda' })).not.toBeChecked();
    await user.click(within(dialog).getByRole('button', { name: 'Expedir' }));

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ courierEmployeeId: SESSION.employeeId, version: 0 });
  });

  it('another courier picked over the default is the one sent', async () => {
    const ana = { id: SESSION.employeeId, code: 'E01', name: 'Ana Silva' };
    deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    couriersEndpoint(json([ana, COURIER.rita]));
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, json(OUT_NOW));
    const rendered = await dispatching();

    await sendRita(rendered);

    await waitFor(() => expect(sent).toHaveLength(1));
    expect(sent[0]!.body).toEqual({ courierEmployeeId: COURIER.rita.id, version: 0 });
  });

  it('a courier who may no longer carry deliveries (NOT_A_COURIER) is refused in Portuguese', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint();
    dispatchEndpoint(DELIVERY.pending.orderId!, () => problem(422, 'NOT_A_COURIER'));
    const rendered = await dispatching();

    await sendRita(rendered);

    expect(await within(rendered.dialog).findByRole('alert')).toHaveTextContent(
      'Este empregado não é estafeta nesta loja. Escolha outro estafeta.',
    );
  });

  it('names each courier with their code, and sends nothing when cancelled', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint();
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, json(OUT_NOW));
    const { user, dialog } = await dispatching();

    // The name is the courier's, the code its description: never glued into one word ("Rita MabundaE05").
    expect(await within(dialog).findByRole('radio', { name: 'João Pereira' })).toHaveAccessibleDescription('E02');
    expect(within(dialog).getByRole('radio', { name: 'Rita Mabunda' })).toHaveAccessibleDescription('E05');
    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(sent).toHaveLength(0);
  });

  it('a store without couriers says so, and nothing can be sent', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint(json([]));
    const { dialog } = await dispatching();

    expect(await within(dialog).findByText(/Esta loja não tem estafetas ativos/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Expedir' })).toBeDisabled();
  });

  it('while the couriers load, a placeholder says so and nothing can be sent', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint(() => new Promise<Response>(() => undefined));
    const { dialog } = await dispatching();

    expect(within(dialog).getByRole('status', { name: 'A carregar os estafetas…' })).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Expedir' })).toBeDisabled();
  });

  it('couriers that fail to load say so, and can be read again', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    const reads = couriersEndpoint(() => problem(500, 'INTERNAL'), json(Object.values(COURIER)));
    const { user, dialog } = await dispatching();

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Não foi possível carregar os estafetas.');
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    expect(await within(dialog).findByRole('radio', { name: /^Rita Mabunda/ })).toBeInTheDocument();
    expect(reads).toHaveLength(2);
  });

  it('a refused dispatch names the reason in the dialog (drafts still to send)', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint();
    dispatchEndpoint(DELIVERY.pending.orderId!, () => problem(422, 'DELIVERY_HAS_DRAFTS'));
    const rendered = await dispatching();

    await sendRita(rendered);

    expect(await within(rendered.dialog).findByRole('alert')).toHaveTextContent(
      'O pedido tem artigos por enviar. Envie-os ou apague-os antes de expedir.',
    );
  });

  it('a delivery changed on another terminal (409) closes the dialog; the board says so and reads again', async () => {
    const reads = deliveriesEndpoint(json([DELIVERY.pending]), json([OUT_NOW]));
    couriersEndpoint();
    dispatchEndpoint(DELIVERY.pending.orderId!, () => problem(409, 'DELIVERY_STATE'));
    const rendered = await dispatching();

    await sendRita(rendered);

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'A entrega foi alterada noutro terminal. O quadro foi atualizado; reveja e tente de novo.',
    );
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(reads.length).toBeGreaterThanOrEqual(2));
  });

  it('a lost answer keeps the courier and the dialog, and "Tentar novamente" resends the same Idempotency-Key', async () => {
    deliveriesEndpoint(json([DELIVERY.pending]));
    couriersEndpoint();
    const sent = dispatchEndpoint(DELIVERY.pending.orderId!, () => HttpResponse.error(), json(OUT_NOW));
    const rendered = await dispatching();
    const { user, dialog } = rendered;

    await sendRita(rendered);
    const retry = await within(dialog).findByRole('button', { name: 'Tentar novamente' });
    expect(within(dialog).getByRole('alert')).toHaveTextContent('Sem ligação — a expedição não foi confirmada.');
    expect(within(dialog).getByRole('radio', { name: /^João Pereira/ })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    await user.click(retry);

    await waitFor(() => expect(sent).toHaveLength(2));
    expect(sent[1]!.idempotencyKey).toBe(sent[0]!.idempotencyKey);
    expect(sent[1]!.body).toEqual({ courierEmployeeId: COURIER.rita.id, version: 0 });
  });
});
