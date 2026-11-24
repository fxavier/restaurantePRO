import { formatMoney } from '@restaurantpro/ui';
import { screen, waitFor, within } from '@testing-library/react';
import { delay, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import {
  BATCH_ID,
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
  subtotalEndpoint,
  subtotalPrinted,
  updateLineEndpoint,
  withLines,
} from '../../test/order';
import { renderPos } from '../../test/render-pos';
import {
  notificationsEndpoint,
  problem,
  recording,
  roundsEndpoint,
  tableMap,
  tablesEndpoint,
  token,
  tokenEndpoint,
} from '../../test/server';
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

type Answer = Parameters<typeof orderEndpoint>[1];

/** The map, M2 tapped: its panel. `maps` answer the map's reads in turn, `catalog` the catalog's. */
async function atM2(...answers: Answer[]) {
  return atM2With({ orders: answers });
}

async function atM2With({
  orders = [],
  maps = [tableMap(MAP)],
  catalog = [],
}: {
  orders?: Answer[];
  maps?: Parameters<typeof tablesEndpoint>;
  catalog?: Answer[];
}) {
  tokenEndpoint(token('jwt-1'));
  const mapReads = tablesEndpoint(...maps);
  catalogEndpoint(...catalog);
  orderEndpoint(ORDER_ID, ...(orders.length ? orders : [json(ORDER)]));
  const rendered = await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });
  await rendered.user.click(await screen.findByRole('button', { name: /^Mesa M2 / }));
  return { ...rendered, mapReads };
}

/** MAP once M2's subtotal is printed: the bill requested. */
const M2_BILL = {
  ...MAP,
  areas: MAP.areas!.map((area) => ({
    ...area,
    tables: area.tables!.map((t) => (t.code === 'M2' ? { ...t, subtotalPrinted: true } : t)),
  })),
};

const panel = () => screen.getByRole('complementary', { name: 'Detalhe da mesa' });
const draftList = () => within(panel()).getByRole('list', { name: 'Por enviar' });

describe('the selected table\'s order', () => {
  it('shows the sent rounds as sent, the drafts as the next round, and the server\'s total', async () => {
    await atM2();

    const round = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    expect(within(panel()).getByRole('heading', { name: 'Ronda 1 · enviada · 12:10' })).toBeInTheDocument();
    expect(round).toHaveTextContent('3 × Bitoque');
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

  it('a round the kitchen has ready says "pronta"; the kitchen is read once for the whole order', async () => {
    const rounds = roundsEndpoint([{ batchId: BATCH_ID, status: 'READY' }]);
    await atM2();

    const sent = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    const kitchen = await within(sent.parentElement!).findByText('pronta');
    expect(kitchen).toHaveClass('text-success-text');
    expect(rounds).toHaveLength(1);
    expect(rounds[0]!.url).toMatch(new RegExp(`/api/v1/pos/production-tickets/rounds\\?orderId=${ORDER_ID}$`));
  });

  it('a round no station has started has no chip', async () => {
    const rounds = roundsEndpoint([{ batchId: BATCH_ID, status: 'QUEUED' }]);
    await atM2();

    const sent = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    await waitFor(() => expect(rounds).toHaveLength(1));
    expect(within(sent.parentElement!).queryByText('pronta')).not.toBeInTheDocument();
    expect(within(sent.parentElement!).queryByText('em preparação')).not.toBeInTheDocument();
  });

  it('when the kitchen cannot be read the rounds show as sent, with no chip and no alert', async () => {
    const rounds = recording<undefined>('get', '/api/v1/pos/production-tickets/rounds', () => problem(500, 'INTERNAL'));
    await atM2();

    expect(await within(panel()).findByRole('heading', { name: 'Ronda 1 · enviada · 12:10' })).toBeInTheDocument();
    await waitFor(() => expect(rounds.length).toBeGreaterThan(0));
    expect(within(panel()).queryByText('pronta')).not.toBeInTheDocument();
    expect(within(panel()).queryByText('em preparação')).not.toBeInTheDocument();
    expect(within(panel()).queryByText('servida')).not.toBeInTheDocument();
    expect(within(panel()).queryByRole('alert')).not.toBeInTheDocument();
    expect(within(panel()).getByRole('button', { name: 'Enviar ronda 2' })).toBeEnabled();
  });

  it('a round sent after a subtotal clears "Subtotal impresso."', async () => {
    const server = serverOrder();
    subtotalEndpoint(ORDER_ID, subtotalPrinted());
    batchEndpoint(ORDER_ID, server.sends());
    const { user } = await atM2(server.read);
    await user.click(await within(panel()).findByRole('button', { name: 'Subtotal' }));
    await waitFor(() => expect(within(panel()).getByRole('status', { name: 'Subtotal' })).toHaveTextContent('Subtotal impresso.'));

    await user.click(within(panel()).getByRole('button', { name: 'Enviar ronda 2' }));

    await waitFor(() => expect(within(panel()).getByRole('status', { name: 'Envio' })).toHaveTextContent('Ronda 2 enviada.'));
    expect(within(panel()).queryByText('Subtotal impresso.')).not.toBeInTheDocument();
  });

  it('a round every station has bumped says "servida", in the info tint and not the green of "pronta"', async () => {
    roundsEndpoint([{ batchId: BATCH_ID, status: 'SERVED' }]);
    await atM2();

    const sent = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    const served = await within(sent.parentElement!).findByText('servida');
    expect(served).toHaveClass('text-info-text');
    expect(served).not.toHaveClass('text-success-text');
    expect(within(sent.parentElement!).queryByText('pronta')).not.toBeInTheDocument();
  });

  it('a round being cooked says "em preparação"', async () => {
    roundsEndpoint([{ batchId: BATCH_ID, status: 'IN_PREPARATION' }]);
    await atM2();

    const sent = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    expect(await within(sent.parentElement!).findByText('em preparação')).toBeInTheDocument();
    expect(within(sent.parentElement!).queryByText('pronta')).not.toBeInTheDocument();
  });

  it('the kitchen read again on a production change shows the round ready', async () => {
    const answers = [[{ batchId: BATCH_ID, status: 'QUEUED' as const }], [{ batchId: BATCH_ID, status: 'READY' as const }]];
    recording<undefined>('get', '/api/v1/pos/production-tickets/rounds', () => HttpResponse.json(answers.length > 1 ? answers.shift() : answers[0]));
    const stream = notificationsEndpoint();
    await atM2();
    const sent = await within(panel()).findByRole('list', { name: 'Ronda 1, 12:10' });
    expect(within(sent.parentElement!).queryByText('pronta')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Ligado'));

    stream.streams[0]!.change('PRODUCTION_TICKETS', id(9001));

    expect(await within(sent.parentElement!).findByText('pronta')).toBeInTheDocument();
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

    expect(await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' })).toHaveTextContent('2 × Água Pequena');
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

  it('"Dividir" opens the bill on its split, "Adicionar artigos" the order screen', async () => {
    const { user, router } = await atM2();

    expect(await within(panel()).findByRole('link', { name: 'Dividir' })).toHaveAttribute(
      'href',
      `/orders/${ORDER_ID}/payment?dividir`,
    );
    await user.click(within(panel()).getByRole('button', { name: 'Adicionar artigos' }));

    expect(router.state.location.pathname).toBe(`/orders/${ORDER_ID}`);
  });

  it('a draft whose catalog could not be read is "Nome indisponível", never "fora do catálogo"', async () => {
    await atM2With({ catalog: [() => problem(500, 'INTERNAL')] });

    const drafts = await within(panel()).findByRole('list', { name: 'Por enviar' });
    await waitFor(() => expect(within(drafts).getAllByText(/Nome indisponível/)).toHaveLength(2));
    expect(drafts).not.toHaveTextContent('Artigo fora do catálogo');
  });
});

describe('"Subtotal" on the table map', () => {
  it('prints the subtotal with a key, says so once the server has, and the tile turns "Conta pedida"', async () => {
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const prints = subtotalEndpoint(ORDER_ID, async () => {
      await released;
      return subtotalPrinted()();
    });
    const { user } = await atM2With({ maps: [tableMap(MAP), tableMap(M2_BILL)] });
    const subtotal = await within(panel()).findByRole('button', { name: 'Subtotal' });

    await user.click(subtotal);

    expect(await within(panel()).findByRole('status', { name: 'Subtotal' })).toHaveTextContent('A imprimir o subtotal…');
    expect(subtotal).toBeDisabled();
    release();

    await waitFor(() => expect(within(panel()).getByRole('status', { name: 'Subtotal' })).toHaveTextContent('Subtotal impresso.'));
    expect(await screen.findByRole('button', { name: /^Mesa M2 .*Conta pedida/ })).toBeInTheDocument();
    expect(prints).toHaveLength(1);
    expect(prints[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('no answer: nothing said printed, and "Tentar novamente" resends the same key', async () => {
    const prints = subtotalEndpoint(ORDER_ID, () => HttpResponse.error(), subtotalPrinted());
    const { user } = await atM2();

    await user.click(await within(panel()).findByRole('button', { name: 'Subtotal' }));

    expect(await within(panel()).findByRole('alert')).toHaveTextContent('Sem ligação — o subtotal não foi confirmado.');
    expect(prints).toHaveLength(1);
    await user.click(within(panel()).getByRole('button', { name: 'Tentar novamente' }));

    await waitFor(() => expect(within(panel()).getByRole('status', { name: 'Subtotal' })).toHaveTextContent('Subtotal impresso.'));
    expect(prints).toHaveLength(2);
    expect(prints[1]!.idempotencyKey).toBe(prints[0]!.idempotencyKey);
  });

  it('a refusal says why, and the next press is a new intent', async () => {
    const prints = subtotalEndpoint(ORDER_ID, () => problem(409, 'ORDER_NOT_BILLABLE'), subtotalPrinted());
    const { user } = await atM2();

    await user.click(await within(panel()).findByRole('button', { name: 'Subtotal' }));

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(/^O subtotal não foi impresso\./);
    await user.click(within(panel()).getByRole('button', { name: 'Subtotal' }));

    await waitFor(() => expect(prints).toHaveLength(2));
    expect(prints[1]!.idempotencyKey).not.toBe(prints[0]!.idempotencyKey);
  });
});
