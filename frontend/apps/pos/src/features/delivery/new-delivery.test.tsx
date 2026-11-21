import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  atDelivery,
  candidateOf,
  createCustomerEndpoint,
  CUSTOMER,
  customerEndpoint,
  customerSearchEndpoint,
  deliveriesEndpoint,
  openOrderEndpoint,
  ZONE,
  zonesEndpoint,
} from '../../test/delivery';
import { catalogEndpoint, created, json, NEW_ORDER, NEW_ORDER_ID, orderEndpoint } from '../../test/order';
import { notificationsEndpoint, problem } from '../../test/server';

const DELIVERY_ORDER = { ...NEW_ORDER, mode: 'DELIVERY' as const, tableId: null, tableCode: null, guests: null };

async function newDelivery({ zones = json(Object.values(ZONE)) }: { zones?: () => Response } = {}) {
  deliveriesEndpoint(json([]));
  zonesEndpoint(zones);
  catalogEndpoint();
  orderEndpoint(NEW_ORDER_ID, json(DELIVERY_ORDER));
  const rendered = await atDelivery();
  await rendered.user.click(screen.getByRole('button', { name: 'Novo pedido' }));
  const dialog = await screen.findByRole('dialog', { name: 'Novo pedido fora de sala' });
  await rendered.user.click(within(dialog).getByRole('radio', { name: /^Delivery/ }));
  return { ...rendered, dialog };
}

describe('a new delivery', () => {
  it('names a known customer, one of their addresses, a zone and a promise, then lands in its order', async () => {
    const searched = customerSearchEndpoint(json([candidateOf(CUSTOMER.marta)]));
    customerEndpoint(CUSTOMER.marta.id, json(CUSTOMER.marta));
    const opened = openOrderEndpoint(created(DELIVERY_ORDER));
    const { user, router, dialog } = await newDelivery();
    const create = within(dialog).getByRole('button', { name: 'Criar entrega' });
    expect(create).toBeDisabled();

    await user.type(within(dialog).getByLabelText('Nome ou telefone do cliente'), 'Marta{Enter}');
    await user.click(await within(dialog).findByRole('button', { name: /^Marta Fontes/ }));
    await user.click(await within(dialog).findByRole('radio', { name: 'Av. Julius Nyerere 1200, 3.º andar, Maputo' }));
    await user.click(within(dialog).getByRole('radio', { name: /^Polana/ }));
    await user.click(within(dialog).getByRole('radio', { name: '30 min' }));
    await user.type(within(dialog).getByLabelText('Notas para o estafeta'), 'Tocar à campainha');
    const before = Date.now();
    await user.click(create);

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`));
    expect(new URL(searched[0]!.url).searchParams.get('q')).toBe('Marta');
    const body = opened[0]!.body;
    expect(body).toEqual({
      mode: 'DELIVERY',
      delivery: {
        customerId: CUSTOMER.marta.id,
        addressId: CUSTOMER.marta.addresses[0]!.id,
        zoneId: ZONE.foz.id,
        promisedAt: expect.any(String),
        notes: 'Tocar à campainha',
      },
    });
    const promised = Date.parse(body.delivery!.promisedAt!) - before;
    expect(promised).toBeGreaterThanOrEqual(30 * 60_000 - 1_000);
    expect(promised).toBeLessThan(31 * 60_000);
    expect(opened[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a customer without a saved address gets one typed for this order', async () => {
    customerSearchEndpoint(json([candidateOf(CUSTOMER.nova)]));
    customerEndpoint(CUSTOMER.nova.id, json(CUSTOMER.nova));
    const opened = openOrderEndpoint(created(DELIVERY_ORDER));
    const { user, router, dialog } = await newDelivery();

    await user.type(within(dialog).getByLabelText('Nome ou telefone do cliente'), 'Joana');
    await user.click(within(dialog).getByRole('button', { name: 'Procurar' }));
    await user.click(await within(dialog).findByRole('button', { name: /^Joana Nova/ }));
    await user.type(await within(dialog).findByLabelText('Morada'), 'Rua da Resistência 300');
    await user.type(within(dialog).getByLabelText('Cidade'), 'Maputo');
    await user.click(within(dialog).getByRole('radio', { name: /^Sommerschield/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Criar entrega' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`));
    expect(opened[0]!.body.delivery).toEqual({
      customerId: CUSTOMER.nova.id,
      address: { line1: 'Rua da Resistência 300', city: 'Maputo' },
      zoneId: ZONE.sommerschield.id,
    });
  });

  it('a new customer is created with a name and phone, then chosen', async () => {
    const createdCustomers = createCustomerEndpoint(json(CUSTOMER.nova));
    customerEndpoint(CUSTOMER.nova.id, json(CUSTOMER.nova));
    const { user, dialog } = await newDelivery();

    await user.click(within(dialog).getByRole('button', { name: 'Novo cliente' }));
    await user.type(within(dialog).getByLabelText('Nome'), ' Joana Nova ');
    await user.type(within(dialog).getByLabelText('Telefone'), '847654321');
    await user.click(within(dialog).getByRole('button', { name: 'Criar cliente' }));

    expect(await within(dialog).findByText('Joana Nova')).toBeInTheDocument();
    expect(await within(dialog).findByLabelText('Morada')).toBeInTheDocument();
    expect(createdCustomers[0]!.body).toEqual({ name: 'Joana Nova', phone: '847654321' });
    expect(createdCustomers[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('a lost answer to "Criar cliente" keeps that intent: no way back to the search, no closing, the same key again', async () => {
    const createdCustomers = createCustomerEndpoint(() => HttpResponse.error(), json(CUSTOMER.nova));
    customerEndpoint(CUSTOMER.nova.id, json(CUSTOMER.nova));
    const { user, dialog } = await newDelivery();

    await user.click(within(dialog).getByRole('button', { name: 'Novo cliente' }));
    await user.type(within(dialog).getByLabelText('Nome'), 'Joana Nova');
    await user.click(within(dialog).getByRole('button', { name: 'Criar cliente' }));
    await within(dialog).findByRole('button', { name: 'Tentar novamente' });

    expect(within(dialog).getByText(/O cliente pode já ter sido criado/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Voltar à pesquisa' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeDisabled();
    expect(within(dialog).getByRole('radio', { name: /^Takeaway/ })).toBeDisabled();
    await user.keyboard('{Escape}');
    expect(screen.getByRole('dialog', { name: 'Novo pedido fora de sala' })).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));
    expect(await within(dialog).findByText('Joana Nova')).toBeInTheDocument();
    expect(createdCustomers).toHaveLength(2);
    expect(createdCustomers[1]!.idempotencyKey).toBe(createdCustomers[0]!.idempotencyKey);
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toBeEnabled();
  });

  it('never offers "Tentar novamente" for a customer while the dialog can still be cancelled, not even for a moment', async () => {
    createCustomerEndpoint(() => HttpResponse.error());
    const { user, dialog } = await newDelivery();
    await user.click(within(dialog).getByRole('button', { name: 'Novo cliente' }));
    await user.type(within(dialog).getByLabelText('Nome'), 'Joana Nova');
    // Every screen the waiter could tap on, as each one is committed, before anything React runs after it.
    const cancellable: boolean[] = [];
    const watch = new MutationObserver(() => {
      if (!within(dialog).queryByRole('button', { name: 'Tentar novamente' })) return;
      cancellable.push(within(dialog).getByRole('button', { name: 'Cancelar' }).hasAttribute('disabled') === false);
    });
    watch.observe(document.body, { subtree: true, childList: true, attributes: true, characterData: true });

    await user.click(within(dialog).getByRole('button', { name: 'Criar cliente' }));
    await within(dialog).findByRole('button', { name: 'Tentar novamente' });
    watch.disconnect();

    expect(cancellable).not.toHaveLength(0);
    expect(cancellable).not.toContain(true);
  });

  it('a refused "Criar cliente" is not the next new customer\'s: back at the search, a new one starts clean', async () => {
    createCustomerEndpoint(() => problem(422, 'VALIDATION_FAILED'));
    const { user, dialog } = await newDelivery();
    await user.click(within(dialog).getByRole('button', { name: 'Novo cliente' }));
    await user.type(within(dialog).getByLabelText('Nome'), 'Joana Nova');
    await user.click(within(dialog).getByRole('button', { name: 'Criar cliente' }));
    expect(await within(dialog).findByRole('alert')).toBeInTheDocument();

    await user.click(within(dialog).getByRole('button', { name: 'Voltar à pesquisa' }));
    await user.click(within(dialog).getByRole('button', { name: 'Novo cliente' }));

    expect(within(dialog).getByLabelText('Nome')).toHaveValue('');
    expect(within(dialog).queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a search without a match says so', async () => {
    customerSearchEndpoint(json([]));
    const { user, dialog } = await newDelivery();

    await user.type(within(dialog).getByLabelText('Nome ou telefone do cliente'), '8499{Enter}');

    expect(await within(dialog).findByText('Nenhum cliente encontrado.')).toBeInTheDocument();
  });

  it('a refusal names the reason, and the order is not opened', async () => {
    customerSearchEndpoint(json([candidateOf(CUSTOMER.marta)]));
    customerEndpoint(CUSTOMER.marta.id, json(CUSTOMER.marta));
    openOrderEndpoint(() => problem(422, 'ZONE_INACTIVE'));
    const { user, router, dialog } = await newDelivery();

    await user.type(within(dialog).getByLabelText('Nome ou telefone do cliente'), 'Marta{Enter}');
    await user.click(await within(dialog).findByRole('button', { name: /^Marta Fontes/ }));
    await user.click(await within(dialog).findByRole('radio', { name: /^Rua do Bagamoyo 10/ }));
    await user.click(within(dialog).getByRole('radio', { name: /^Polana/ }));
    await user.click(within(dialog).getByRole('button', { name: 'Criar entrega' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('A zona de entrega está inativa. Escolha outra.');
    expect(router.state.location.pathname).toBe('/delivery');
  });

  it('zones that cannot be read say so, with "Tentar novamente"', async () => {
    const { dialog } = await newDelivery({ zones: () => problem(500, 'INTERNAL') });

    expect(await within(dialog).findByText(/Não foi possível carregar as zonas de entrega/)).toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: 'Tentar novamente' })).toBeInTheDocument();
  });

  it('a store without active zones says a manager creates them', async () => {
    const { dialog } = await newDelivery({ zones: json([]) });

    expect(await within(dialog).findByText('Esta loja não tem zonas de entrega ativas. Um gestor cria-as no BackOffice.')).toBeInTheDocument();
  });

  it('"Sem ligação" disables "Criar entrega"', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    const { dialog } = await newDelivery();
    // The status bar sits behind the modal dialog, hidden from the accessibility tree meanwhile.
    await waitFor(() =>
      expect(screen.getByRole('status', { name: 'Ligação', hidden: true })).toHaveTextContent('Sem ligação'),
    );

    expect(within(dialog).getByRole('button', { name: 'Criar entrega' })).toBeDisabled();
    expect(within(dialog).getByText('Sem ligação — nenhuma operação é confirmada')).toBeInTheDocument();
  });
});
