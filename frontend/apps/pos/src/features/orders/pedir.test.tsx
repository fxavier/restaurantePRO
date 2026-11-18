import { act, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  batchEndpoint,
  type BatchView,
  json,
  LINE,
  type LineView,
  ORDER,
  ORDER_ID,
  type OrderView,
  withLines,
} from '../../test/order';
import { atOrder, drafts, panel, ready } from '../../test/order-screen';
import { notificationsEndpoint, problem } from '../../test/server';
import { id } from '../../test/table-map';

const ROUND_ID = id(551);

/** The two drafts, as the server confirms them into round 2. */
const confirmed = (line: LineView): LineView => ({ ...line, status: 'CONFIRMED', batchId: ROUND_ID, preview: false, version: line.version! + 1 });
const ROUND: BatchView = {
  id: ROUND_ID,
  orderId: ORDER_ID,
  batchNo: 2,
  status: 'CONFIRMED',
  confirmedAt: '2026-11-18T10:20:00Z',
  orderVersion: 5,
  lines: [confirmed(LINE.water), confirmed(LINE.coffee)],
};
const SENT: OrderView = withLines(ORDER, [...ROUND.lines!, LINE.steak], {
  version: 5,
  catalogVersion: undefined,
  batches: [...ORDER.batches!, ROUND],
});

/**
 * The order as the server has it now: every read answers the current value, so a read more or less (a reconnect)
 * never shifts what a test sees. `sends(view)` answers a round and moves the server to `SENT` first.
 */
function serverOrder(initial: OrderView = ORDER) {
  const state = { current: initial };
  return {
    state,
    read: () => HttpResponse.json(state.current),
    sends: (view: BatchView = ROUND) => () => {
      state.current = SENT;
      return HttpResponse.json(view, { status: 201 });
    },
  };
}

const pedir = () => within(panel()).getByRole('button', { name: 'Pedir' });

/** The backend's uncoded 409: a stale version, or the same key still running. */
const uncoded409 = () => problem(409, 'HTTP_409');

describe('"Pedir"', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('sends the drafts as one round with the order, catalog and lines it shows, then shows the server\'s round', async () => {
    const server = serverOrder();
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const batches = batchEndpoint(ORDER_ID, async () => {
      await released;
      return server.sends()();
    });
    const { user } = await atOrder([server.read]);
    await ready();

    await user.click(pedir());

    // In flight: nothing moves until the server answers (no optimistic round).
    expect(await within(panel()).findByRole('status', { name: 'A enviar' })).toHaveTextContent('A enviar a ronda…');
    expect(pedir()).toBeDisabled();
    expect(within(drafts()).getAllByRole('listitem')).toHaveLength(2);
    expect(within(panel()).queryByRole('list', { name: /^Ronda 2/ })).not.toBeInTheDocument();
    release();

    const round = await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' });
    expect(within(round).getAllByRole('listitem').map((line) => line.textContent)).toEqual([
      expect.stringContaining('2 × Água Pequena'),
      expect.stringContaining('1 × Bica'),
    ]);
    expect(within(panel()).getByText('Sem linhas por enviar.')).toBeInTheDocument();
    expect(within(panel()).getByRole('status', { name: 'Envio' })).toHaveTextContent('Ronda 2 enviada.');
    expect(batches).toHaveLength(1);
    expect(batches[0]!.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(batches[0]!.body).toEqual({ orderVersion: 4, catalogVersion: 7, lineIds: [LINE.water.id, LINE.coffee.id] });
  });

  it('sends back the promotion release the previews came from', async () => {
    const server = serverOrder({ ...ORDER, promotionVersion: 3 });
    const batches = batchEndpoint(ORDER_ID, server.sends());
    const { user } = await atOrder([server.read]);
    await ready();

    await user.click(pedir());

    await waitFor(() => expect(batches).toHaveLength(1));
    expect(batches[0]!.body).toMatchObject({ promotionVersion: 3 });
  });

  it('a double tap sends one request', async () => {
    const server = serverOrder();
    let release!: () => void;
    const released = new Promise<void>((resolve) => (release = resolve));
    const batches = batchEndpoint(ORDER_ID, async () => {
      await released;
      return server.sends()();
    });
    const { user } = await atOrder([server.read]);
    await ready();

    await user.dblClick(pedir());
    release();

    await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' });
    expect(batches).toHaveLength(1);
  });

  it('is disabled with nothing to send', async () => {
    await atOrder([json(withLines(ORDER, [LINE.steak], { catalogVersion: undefined }))]);
    await ready();

    expect(pedir()).toBeDisabled();
  });

  it('"Sem ligação" disables it', async () => {
    notificationsEndpoint(() => HttpResponse.error());
    await atOrder();
    await ready();

    await waitFor(() => expect(screen.getByRole('status', { name: 'Ligação' })).toHaveTextContent('Sem ligação'));
    expect(pedir()).toBeDisabled();
  });

  it('a network failure shows nothing as sent and offers "Tentar novamente" with the same key and body', async () => {
    const server = serverOrder();
    const batches = batchEndpoint(ORDER_ID, () => HttpResponse.error(), server.sends());
    const { user } = await atOrder([server.read]);
    await ready();

    await user.click(pedir());

    expect(await within(panel()).findByRole('alert')).toHaveTextContent('Sem ligação — a ronda não foi confirmada.');
    expect(within(drafts()).getAllByRole('listitem')).toHaveLength(2);
    expect(within(panel()).queryByRole('button', { name: 'Pedir' })).not.toBeInTheDocument();
    await user.click(within(panel()).getByRole('button', { name: 'Tentar novamente' }));

    await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' });
    expect(batches).toHaveLength(2);
    expect(batches[1]!.idempotencyKey).toBe(batches[0]!.idempotencyKey);
    expect(batches[1]!.body).toEqual(batches[0]!.body);
  });

  it('a 504 is an unknown outcome too; "Descartar" gives it up and the next "Pedir" is a new intent', async () => {
    const server = serverOrder();
    const batches = batchEndpoint(ORDER_ID, () => problem(504, 'HTTP_504'), server.sends());
    const { user } = await atOrder([server.read]);
    await ready();

    await user.click(pedir());
    expect(await within(panel()).findByRole('alert')).toHaveTextContent('Sem ligação — a ronda não foi confirmada.');
    await user.click(within(panel()).getByRole('button', { name: 'Descartar' }));
    await user.click(pedir());

    await waitFor(() => expect(batches).toHaveLength(2));
    expect(batches[1]!.idempotencyKey).not.toBe(batches[0]!.idempotencyKey);
  });

  it('a 409 "in progress" waits and resends the same key, up to three times', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    // The order stays at the version sent: the 409 is the same key still running, not a stale version.
    const server = serverOrder();
    const batches = batchEndpoint(ORDER_ID, uncoded409, uncoded409, uncoded409, server.sends());
    const { user } = await atOrder([server.read], { user: { advanceTimers: vi.advanceTimersByTime } });
    await ready();

    await user.click(pedir());
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      await waitFor(() => expect(batches).toHaveLength(attempt));
      await act(() => vi.advanceTimersByTimeAsync(1_000));
    }

    await waitFor(() => expect(batches).toHaveLength(4));
    expect(new Set(batches.map((batch) => batch.idempotencyKey)).size).toBe(1);
    await within(panel()).findByRole('list', { name: 'Ronda 2, 12:20' });
  });

  it('still "in progress" after three resends: the same key waits for "Tentar novamente"', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
    const batches = batchEndpoint(ORDER_ID, uncoded409);
    const { user } = await atOrder([json(ORDER)], { user: { advanceTimers: vi.advanceTimersByTime } });
    await ready();

    await user.click(pedir());
    for (let attempt = 1; attempt <= 3; attempt += 1) {
      await waitFor(() => expect(batches).toHaveLength(attempt));
      await act(() => vi.advanceTimersByTimeAsync(1_000));
    }

    expect(await within(panel()).findByRole('alert')).toHaveTextContent('A ronda ainda está a ser processada pelo servidor.');
    expect(batches).toHaveLength(4);
    expect(within(panel()).getByRole('button', { name: 'Tentar novamente' })).toBeEnabled();
  });

  it('a version conflict reads the order again, shows it, and the next "Pedir" sends the current version', async () => {
    const extra: LineView = { ...LINE.coffee, id: id(804), quantity: 2, version: 0 };
    const changed = withLines(ORDER, [LINE.water, LINE.coffee, extra, LINE.steak], { version: 5 });
    const server = serverOrder();
    const batches = batchEndpoint(
      ORDER_ID,
      () => {
        server.state.current = changed; // another terminal added a line first
        return uncoded409();
      },
      server.sends(),
    );
    const { user } = await atOrder([server.read]);
    await ready();

    await user.click(pedir());

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'O pedido foi alterado noutro terminal. Reveja-o e carregue em Pedir de novo.',
    );
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')).toHaveLength(3));
    expect(batches).toHaveLength(1);

    await user.click(pedir());

    await waitFor(() => expect(batches).toHaveLength(2));
    expect(batches[1]!.idempotencyKey).not.toBe(batches[0]!.idempotencyKey);
    expect(batches[1]!.body).toMatchObject({ orderVersion: 5, lineIds: [LINE.water.id, LINE.coffee.id, extra.id] });
  });

  it('PRICES_CHANGED reads the order again and shows each new price beside the one shown before', async () => {
    const repriced = withLines(ORDER, [{ ...LINE.water, gross: { amount: 150, currency: 'MZN' } }, LINE.coffee, LINE.steak]);
    const server = serverOrder();
    const batches = batchEndpoint(
      ORDER_ID,
      () => {
        server.state.current = repriced;
        return problem(409, 'PRICES_CHANGED');
      },
      server.sends(),
    );
    const { user, catalog } = await atOrder([server.read]);
    await ready();
    const catalogReads = catalog.length;

    await user.click(pedir());

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'Os preços mudaram. Reveja os novos valores e carregue em Pedir de novo.',
    );
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')[0]).toHaveTextContent('150,00 MTn'));
    const [water, coffee] = within(drafts()).getAllByRole('listitem');
    expect(water).toHaveTextContent('Preço alterado, antes 120,00 MTn');
    expect(coffee).not.toHaveTextContent('Preço alterado');
    await waitFor(() => expect(catalog.length).toBeGreaterThan(catalogReads));

    await user.click(pedir());

    await waitFor(() => expect(batches).toHaveLength(2));
    expect(batches[1]!.idempotencyKey).not.toBe(batches[0]!.idempotencyKey);
  });

  it('CATALOG_CHANGED reads the catalog and the order again, then the waiter confirms again', async () => {
    const republished = withLines(ORDER, [LINE.water, { ...LINE.coffee, gross: { amount: 55, currency: 'MZN' } }, LINE.steak], {
      catalogVersion: 8,
    });
    const server = serverOrder();
    const batches = batchEndpoint(
      ORDER_ID,
      () => {
        server.state.current = republished;
        return problem(409, 'CATALOG_CHANGED');
      },
      server.sends(),
    );
    const { user, catalog } = await atOrder([server.read]);
    await ready();
    const catalogReads = catalog.length;

    await user.click(pedir());

    expect(await within(panel()).findByRole('alert')).toHaveTextContent(
      'O catálogo foi atualizado. Reveja os novos valores e carregue em Pedir de novo.',
    );
    await waitFor(() => expect(catalog.length).toBeGreaterThan(catalogReads));
    await waitFor(() => expect(within(drafts()).getAllByRole('listitem')[1]).toHaveTextContent('Preço alterado, antes 50,00 MTn'));

    await user.click(pedir());

    await waitFor(() => expect(batches).toHaveLength(2));
    expect(batches[1]!.body).toMatchObject({ catalogVersion: 8 });
  });

  it('a business refusal says why, reads the order again, and keeps nothing to retry', async () => {
    const batches = batchEndpoint(ORDER_ID, () => problem(409, 'ORDER_NOT_OPEN'));
    const { user, order } = await atOrder();
    await ready();
    const reads = order.length;

    await user.click(pedir());

    expect(await within(panel()).findByRole('alert')).toHaveTextContent('A ronda não foi enviada. Este pedido já não está aberto.');
    await waitFor(() => expect(order.length).toBeGreaterThan(reads));
    expect(within(panel()).queryByRole('button', { name: 'Tentar novamente' })).not.toBeInTheDocument();
    expect(batches).toHaveLength(1);
  });
});
