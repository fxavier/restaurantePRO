import { screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  catalogEndpoint,
  created,
  json,
  NEW_ORDER,
  NEW_ORDER_ID,
  ORDER,
  ORDER_ID,
  openOrdersEndpoint,
  openTableEndpoint,
  orderEndpoint,
} from '../../test/order';
import { renderPos } from '../../test/render-pos';
import { notificationsEndpoint, problem, tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { id, M1_OCCUPIED, MAP } from '../../test/table-map';

/** MAP with M1 locked while still free. */
const M1_LOCKED = {
  ...MAP,
  areas: MAP.areas!.map((area) => ({
    ...area,
    tables: area.tables!.map((t) => (t.code === 'M1' ? { ...t, locked: true } : t)),
  })),
};

async function atTables(map = MAP) {
  tokenEndpoint(token('jwt-1'));
  const tables = tablesEndpoint(tableMap(map));
  catalogEndpoint();
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  await screen.findByRole('button', { name: /^Mesa M1 / });
  return { ...rendered, tables };
}

const guestsDialog = () => screen.findByRole('dialog', { name: 'Abrir mesa M1' });

async function enterGuests(user: Awaited<ReturnType<typeof atTables>>['user'], digits: string) {
  const pad = within(await guestsDialog()).getByRole('group', { name: 'Teclado numérico' });
  for (const digit of digits) await user.click(within(pad).getByRole('button', { name: digit }));
}

describe('opening a table', () => {
  it('a free table asks for the guests on a numeric pad, opens with them, and lands in its order', async () => {
    const opened = openTableEndpoint(id(1), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, json(NEW_ORDER));
    const { user, router } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    const dialog = await guestsDialog();
    expect(within(dialog).getByRole('button', { name: 'Abrir mesa' })).toBeDisabled();
    await enterGuests(user, '2');
    expect(within(dialog).getByLabelText('Número de pessoas')).toHaveValue('2');
    await user.click(within(dialog).getByRole('button', { name: 'Abrir mesa' }));

    expect(await screen.findByRole('heading', { name: 'Mesa M1' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`);
    expect(opened).toHaveLength(1);
    expect(opened[0]!.body).toEqual({ guests: 2 });
    expect(opened[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(opened[0]!.posSession).toBe('pos-session-ana');
  });

  it('the guests can be typed on a keyboard too', async () => {
    const opened = openTableEndpoint(id(1), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, json(NEW_ORDER));
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    await user.type(within(await guestsDialog()).getByLabelText('Número de pessoas'), '12{Enter}');

    await screen.findByRole('heading', { name: 'Mesa M1' });
    expect(opened[0]!.body).toEqual({ guests: 12 });
  });

  it('an occupied table lands in its open order', async () => {
    const lookups = openOrdersEndpoint([NEW_ORDER, ORDER]);
    orderEndpoint(ORDER_ID, json(ORDER));
    const { user, router } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M2 / }));

    expect(await screen.findByRole('heading', { name: 'Mesa M2' })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
    expect(new URL(lookups[0]!.url).searchParams.get('lifecycle')).toBe('OPEN');
    expect(new URL(lookups[0]!.url).searchParams.get('size')).toBe('200');
  });

  it('an occupied table without an open order any more says so, with the way back', async () => {
    openOrdersEndpoint([NEW_ORDER]);
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M2 / }));

    expect(await screen.findByRole('alert')).toHaveTextContent('A mesa M2 já não tem um pedido aberto.');
    await user.click(screen.getByRole('link', { name: 'Voltar às mesas' }));
    expect(await screen.findByRole('button', { name: /^Mesa M2 / })).toBeInTheDocument();
  });

  it('a truncated page of open orders without the table\'s is a failure to retry, not "no open order"', async () => {
    const lookups = openOrdersEndpoint([NEW_ORDER], 201);
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M2 / }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Não foi possível encontrar o pedido da mesa M2 entre os pedidos abertos.',
    );
    expect(screen.queryByText(/já não tem um pedido aberto/)).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Tentar novamente' }));
    await waitFor(() => expect(lookups).toHaveLength(2));
  });

  it('a table this terminal may have opened (an unknown outcome, then "Cancelar") goes to its order when found taken', async () => {
    tokenEndpoint(token('jwt-1'));
    let map = MAP;
    tablesEndpoint(() => HttpResponse.json(map));
    catalogEndpoint();
    const opened = openTableEndpoint(
      id(1),
      () => HttpResponse.error(),
      () => {
        map = M1_OCCUPIED;
        return problem(409, 'TABLE_OCCUPIED');
      },
    );
    openOrdersEndpoint([{ ...NEW_ORDER, tableId: id(1) }]);
    orderEndpoint(NEW_ORDER_ID, json(NEW_ORDER));
    const { user, router } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });

    await user.click(await screen.findByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '2');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));
    await within(await guestsDialog()).findByRole('alert');
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    await user.click(screen.getByRole('button', { name: /^Mesa M1 Livre/ }));
    await enterGuests(user, '2');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));

    await waitFor(() => expect(router.state.location.pathname).toBe(`/orders/${NEW_ORDER_ID}`));
    expect(await screen.findByRole('complementary', { name: 'Pedido' })).toHaveTextContent('Mesa M1');
    expect(opened).toHaveLength(2);
  });

  it('a locked free table is refused with the reason, without asking for guests', async () => {
    const { user } = await atTables(M1_LOCKED);

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));

    const dialog = await screen.findByRole('dialog', { name: 'Mesa M1 bloqueada' });
    expect(within(dialog).getByText(/Um gerente tem de a desbloquear/)).toBeInTheDocument();
    expect(within(dialog).queryByRole('group', { name: 'Teclado numérico' })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Fechar' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('a table locked meanwhile is refused by the server with the reason', async () => {
    openTableEndpoint(id(1), () => problem(422, 'TABLE_LOCKED'));
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '2');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));

    expect(await within(await guestsDialog()).findByRole('alert')).toHaveTextContent(
      'A mesa está bloqueada. Um gerente tem de a desbloquear antes de a abrir.',
    );
  });

  it('a table just taken on another terminal (409) is refused, and the map is read again', async () => {
    tokenEndpoint(token('jwt-1'));
    const tables = tablesEndpoint(tableMap(MAP), tableMap(M1_OCCUPIED));
    catalogEndpoint();
    openTableEndpoint(id(1), () => problem(409, 'TABLE_OCCUPIED'));
    const { user } = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });

    await user.click(await screen.findByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '4');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));

    expect(await within(await guestsDialog()).findByRole('alert')).toHaveTextContent(
      'A mesa acabou de ser aberta noutro terminal.',
    );
    await user.click(screen.getByRole('button', { name: 'Cancelar' }));
    expect(await screen.findByRole('button', { name: /^Mesa M1 Ocupada/ })).toBeInTheDocument();
    expect(tables.length).toBeGreaterThanOrEqual(2);
  });

  it('a network failure offers "Tentar novamente", which resends with the same Idempotency-Key', async () => {
    const opened = openTableEndpoint(id(1), () => HttpResponse.error(), created(NEW_ORDER));
    orderEndpoint(NEW_ORDER_ID, json(NEW_ORDER));
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '2');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));

    const dialog = await guestsDialog();
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Sem ligação — a abertura da mesa não foi confirmada.');
    await user.click(within(dialog).getByRole('button', { name: 'Tentar novamente' }));

    await screen.findByRole('heading', { name: 'Mesa M1' });
    expect(opened).toHaveLength(2);
    expect(opened[1]!.idempotencyKey).toBe(opened[0]!.idempotencyKey);
  });

  it('a new opening after a cancelled one is a new intent, with a new key', async () => {
    const opened = openTableEndpoint(id(1), () => problem(422, 'TABLE_LOCKED'));
    const { user } = await atTables();

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '2');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));
    await within(await guestsDialog()).findByRole('alert');
    await user.click(within(await guestsDialog()).getByRole('button', { name: 'Abrir mesa' }));

    await waitFor(() => expect(opened).toHaveLength(2));
    expect(opened[1]!.idempotencyKey).not.toBe(opened[0]!.idempotencyKey);
  });

  it('"Sem ligação" disables "Abrir mesa"', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    const { user } = await atTables();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));

    await user.click(screen.getByRole('button', { name: /^Mesa M1 / }));
    await enterGuests(user, '2');

    const dialog = await guestsDialog();
    expect(within(dialog).getByRole('button', { name: 'Abrir mesa' })).toBeDisabled();
    expect(within(dialog).getByText('Sem ligação — nenhuma operação é confirmada')).toBeInTheDocument();
  });
});
