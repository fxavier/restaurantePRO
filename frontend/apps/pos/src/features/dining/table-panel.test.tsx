import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  batchEndpoint,
  type BatchView,
  catalogEndpoint,
  json,
  LINE,
  type LineView,
  ORDER,
  ORDER_ID,
  orderEndpoint,
  type OrderView,
  updateLineEndpoint,
  withLines,
} from '../../test/order';
import { renderPos } from '../../test/render-pos';
import { problem, tableMap, tablesEndpoint, token, tokenEndpoint } from '../../test/server';
import { id, MAP } from '../../test/table-map';

// The table map's right panel for an occupied table (light reference, 2026-11-23): the open order's rounds, the draft
// round with its quantity steppers, the server's total, the ways to the bill, and "Enviar ronda N".

const ROUND_ID = id(551);
const confirmed = (line: LineView): LineView => ({ ...line, status: 'CONFIRMED', batchId: ROUND_ID, preview: false, version: line.version + 1 });
const ROUND: BatchView = {
  id: ROUND_ID,
  orderId: ORDER_ID,
  batchNo: 2,
  status: 'CONFIRMED',
  confirmedAt: '2026-11-18T10:20:00Z',
  confirmedByEmployeeId: id(77),
  orderVersion: 5,
  lines: [confirmed(LINE.water), confirmed(LINE.coffee)],
};
const SENT: OrderView = withLines(ORDER, [...ROUND.lines!, LINE.steak], {
  version: 5,
  catalogVersion: undefined,
  draftGross: undefined,
  batches: [...ORDER.batches!, ROUND],
});

/** The order as the server has it now; `sends` answers a round and moves the server to SENT first. */
function serverOrder(initial: OrderView = ORDER) {
  const state = { current: initial };
  return {
    state,
    read: () => HttpResponse.json(state.current),
    sends: () => () => {
      state.current = SENT;
      return HttpResponse.json(ROUND, { status: 201 });
    },
  };
}

/** The map, M2 tapped: its panel. */
async function atM2(...answers: Parameters<typeof orderEndpoint>[1][]) {
  tokenEndpoint(token('jwt-1'));
  tablesEndpoint(tableMap(MAP));
  catalogEndpoint();
  orderEndpoint(ORDER_ID, ...(answers.length ? answers : [json(ORDER)]));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  await rendered.user.click(await screen.findByRole('button', { name: /^Mesa M2 / }));
  return rendered;
}

const panel = () => screen.getByRole('complementary', { name: 'Detalhe da mesa' });
const draftList = () => within(panel()).getByRole('list', { name: 'Por enviar' });

describe('the selected table\'s order', () => {
  it('shows the sent rounds as sent, the drafts as the next round, and the server\'s total', async () => {
    await atM2();

    const round = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    expect(within(panel()).getByRole('heading', { name: 'Ronda 1 · enviada · 12:10' })).toBeInTheDocument();
    expect(round).toHaveTextContent('3× Bitoque');
    expect(within(panel()).getByRole('heading', { name: 'Ronda 2 · rascunho' })).toBeInTheDocument();
    // A draft's name comes from the catalog, read beside the order.
    await within(draftList()).findByText('2× Água Pequena');
    expect(within(draftList()).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      expect.stringContaining('2× Água Pequena'),
      expect.stringContaining('1× Bica'),
    ]);
    expect(within(panel()).getByRole('group', { name: 'Total' })).toHaveTextContent(
      formatMoney({ amount: '1350.00', currency: 'MZN' }).replace(/\s/g, ' '),
    );
    expect(within(panel()).getByText('Não inclui as linhas por enviar')).toBeInTheDocument();
  });

  it('a loading order is announced; a failed read offers "Tentar novamente"', async () => {
    const { user } = await atM2(
      async () => {
        await delay(50);
        return problem(500, 'INTERNAL');
      },
      json(ORDER),
    );

    expect(within(panel()).getByRole('status', { name: 'A carregar…' })).toBeInTheDocument();
    await user.click(await within(panel()).findByRole('button', { name: 'Tentar novamente' }));

    expect(await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' })).toBeInTheDocument();
  });

  it('an order with nothing on it says so, and has nothing to send', async () => {
    await atM2(json(withLines(ORDER, [], { batches: [], draftGross: undefined, catalogVersion: undefined })));

    expect(await within(panel()).findByText('Nada enviado ainda.')).toBeInTheDocument();
    expect(within(panel()).getByText('Sem linhas por enviar.')).toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: 'Enviar ronda 1' })).toBeDisabled();
  });

  it('"Enviar ronda 2" sends the drafts as one round, with a key, and shows the round once the server has it', async () => {
    const server = serverOrder();
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const batches = batchEndpoint(ORDER_ID, async () => {
      await released;
      return server.sends()();
    });
    const { user } = await atM2(server.read);
    const send = await within(panel()).findByRole('button', { name: 'Enviar ronda 2' });

    await user.click(send);

    // In flight: disabled, and nothing shown as sent before the server says so.
    expect(await within(panel()).findByRole('status', { name: 'A enviar' })).toBeInTheDocument();
    expect(send).toBeDisabled();
    expect(within(panel()).queryByRole('list', { name: /^Ronda 2,/ })).not.toBeInTheDocument();
    release();

    expect(await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' })).toHaveTextContent('2× Água Pequena');
    expect(within(panel()).getByRole('status', { name: 'Envio' })).toHaveTextContent('Ronda 2 enviada.');
    expect(batches).toHaveLength(1);
    expect(batches[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(batches[0]!.body).toEqual({
      orderVersion: 4,
      catalogVersion: 7,
      lineIds: [LINE.water.id, LINE.coffee.id],
      expectedGross: '170.00',
    });
  });

  it('a send with no answer keeps its key: "Tentar novamente" resends the same intent', async () => {
    const server = serverOrder();
    const batches = batchEndpoint(ORDER_ID, () => HttpResponse.error(), server.sends());
    const { user } = await atM2(server.read);

    await user.click(await within(panel()).findByRole('button', { name: 'Enviar ronda 2' }));
    await user.click(await within(panel()).findByRole('button', { name: 'Tentar novamente' }, { timeout: 5_000 }));

    await waitFor(() => expect(within(panel()).getByRole('status', { name: 'Envio' })).toHaveTextContent('Ronda 2 enviada.'));
    expect(new Set(batches.map((batch) => batch.idempotencyKey)).size).toBe(1);
  });

  it('a draft\'s "+" asks the server for one more, shows the line as "a confirmar" meanwhile, then the server\'s line', async () => {
    const server = serverOrder();
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const updates = updateLineEndpoint(ORDER_ID, LINE.coffee.id, async () => {
      await released;
      server.state.current = withLines(ORDER, [LINE.water, { ...LINE.coffee, quantity: '2.000', version: 4 }, LINE.steak]);
      return HttpResponse.json({});
    });
    const { user } = await atM2(server.read);

    await user.click(await within(panel()).findByRole('button', { name: 'Aumentar Bica' }));

    const coffee = within(draftList()).getAllByRole('listitem')[1]!;
    expect(await within(coffee).findByText('a confirmar')).toBeInTheDocument();
    expect(coffee).toHaveTextContent('1× Bica');
    expect(within(panel()).getByRole('button', { name: 'Diminuir Água Pequena' })).toBeDisabled();
    release();

    await waitFor(() => expect(within(draftList()).getAllByRole('listitem')[1]).toHaveTextContent('2× Bica'));
    expect(within(draftList()).queryByText('a confirmar')).not.toBeInTheDocument();
    expect(updates[0]!.body).toEqual({ version: 3, quantity: '2', notes: undefined, variantId: undefined });
  });

  it('a draft of one has no "−" to take it to zero (removing is on the order screen)', async () => {
    await atM2();

    expect(await within(panel()).findByRole('button', { name: 'Diminuir Bica' })).toBeDisabled();
    expect(within(panel()).getByRole('button', { name: 'Diminuir Água Pequena' })).toBeEnabled();
  });

  it('"Subtotal" and "Dividir" go to the bill, "Adicionar artigos" to the order screen', async () => {
    const { user, router } = await atM2();

    expect(await within(panel()).findByRole('link', { name: 'Subtotal' })).toHaveAttribute('href', `/orders/${ORDER_ID}/payment`);
    expect(within(panel()).getByRole('link', { name: 'Dividir' })).toHaveAttribute('href', `/orders/${ORDER_ID}/payment`);
    await user.click(within(panel()).getByRole('button', { name: 'Adicionar artigos' }));

    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
  });
});
