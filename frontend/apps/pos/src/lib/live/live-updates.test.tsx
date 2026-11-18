import { act, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  notificationsEndpoint,
  posLogoutEndpoint,
  problem,
  SESSION,
  tablesEndpoint,
  token,
  tokenEndpoint,
} from '../../test/server';
import { renderPos } from '../../test/render-pos';
import { M1_OCCUPIED, MAP } from '../../test/table-map';

const connection = () => screen.getByRole('status', { name: 'Ligação' });
const m1 = () => screen.getByRole('button', { name: /^Mesa M1 / });

/** Fake timers that still let MSW and React run; the idle logout (90 s) bounds how far one test may advance. */
function fakeTimers() {
  vi.useFakeTimers({
    toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'Date'],
    shouldAdvanceTime: true,
  });
}

const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms));

/** On the map, with the stream answering `notifications`; `show` changes what the map endpoint answers from then on. */
async function onTheMap(...notifications: Parameters<typeof notificationsEndpoint>) {
  tokenEndpoint(token('jwt-1'));
  let current = MAP;
  const requests = tablesEndpoint(() => HttpResponse.json(current));
  const stream = notificationsEndpoint(...notifications);
  const rendered = await renderPos({
    device: 'enrolled',
    loggedIn: true,
    path: '/tables',
    user: { advanceTimers: vi.advanceTimersByTime },
  });
  await screen.findByRole('button', { name: /^Mesa M1 / });
  if ((notifications[0] ?? 'stream') === 'stream') {
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
    // Opening the stream reads the POS queries again (the first time too); wait until that settled.
    await waitFor(() => expect(rendered.queryClient.isFetching()).toBe(0));
  }
  const show = (map: typeof MAP) => void (current = map);
  return { ...rendered, requests, show, ...stream };
}

describe('live updates', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('subscribes once the employee is in, with the terminal token and the POS session, and shows "Ligado"', async () => {
    const { streams } = await onTheMap();

    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
    expect(streams).toHaveLength(1);
    expect(streams[0]).toMatchObject({ bearer: 'Bearer jwt-1', posSession: SESSION.posSession });
  });

  it('a TABLES change reads the map again; the payload itself is never shown', async () => {
    const { streams, requests, show } = await onTheMap();
    const before = requests.length;

    show(M1_OCCUPIED);
    streams[0]!.change('TABLES');

    await waitFor(() => expect(m1()).toHaveAccessibleName(/^Mesa M1 Ocupada 2 pessoas/));
    expect(requests).toHaveLength(before + 1);
  });

  it('an ORDER change reads the map again; a CATALOG change does not', async () => {
    const { streams, requests } = await onTheMap();
    const before = requests.length;

    streams[0]!.change('CATALOG');
    streams[0]!.change('ORDER', '6f1c2a9e-0000-4000-8000-00000000abcd');

    await waitFor(() => expect(requests).toHaveLength(before + 1));
  });

  it('shows "Sem ligação" when the stream drops, and reads the map every 15 s meanwhile', async () => {
    fakeTimers();
    const { streams, requests } = await onTheMap('stream', () => HttpResponse.error());
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    streams[0]!.drop();

    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));
    // Only the status bar says so: the full banner is for an unreachable backend or Keycloak.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    const before = requests.length;
    await advance(14_000);
    expect(requests).toHaveLength(before);
    await advance(1_000);
    expect(requests).toHaveLength(before + 1);
    await advance(15_000);
    expect(requests).toHaveLength(before + 2);
  });

  it('reconnects with backoff 1, 2, 5, 10, 15 s, then every 15 s', async () => {
    fakeTimers();
    const at: number[] = [];
    const { streams } = await onTheMap('stream', () => {
      at.push(Date.now());
      return problem(503, 'UNAVAILABLE');
    });
    const dropped = Date.now();

    streams[0]!.drop();
    await advance(1_000 + 2_000 + 5_000 + 10_000 + 15_000 + 15_000 + 500);

    // Each wait counts from the failure before it, a few milliseconds after that request.
    const waits = [at[0]! - dropped, ...at.slice(1).map((time, index) => time - at[index]!)];
    expect(waits).toHaveLength(6);
    waits.forEach((wait, index) => expect(wait).toBeCloseTo([1_000, 2_000, 5_000, 10_000, 15_000, 15_000][index]!, -2));
  });

  it('on reconnect reads every POS query again, shows "Ligado" and stops polling', async () => {
    fakeTimers();
    const { streams, requests, show } = await onTheMap('stream', () => HttpResponse.error(), 'stream');
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
    streams[0]!.drop();
    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));
    const before = requests.length;
    show(M1_OCCUPIED);

    await advance(1_000); // the first retry fails
    await advance(2_000); // the second opens the stream
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    expect(streams).toHaveLength(2);
    await waitFor(() => expect(m1()).toHaveAccessibleName(/^Mesa M1 Ocupada/));
    expect(requests).toHaveLength(before + 1);
    await advance(30_000);
    expect(requests).toHaveLength(before + 1);
  });

  it('a stream the server completes (its 30-minute timeout) is opened again', async () => {
    fakeTimers();
    const { streams } = await onTheMap();
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    streams[0]!.end();
    await advance(1_000);

    await waitFor(() => expect(streams).toHaveLength(2));
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
  });

  it('a stream silent for 60 s (no heartbeat) counts as down; heartbeats keep it up', async () => {
    fakeTimers();
    const { streams } = await onTheMap('stream', () => HttpResponse.error());
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    await advance(25_000);
    streams[0]!.heartbeat();
    await advance(50_000);
    expect(connection()).toHaveTextContent('Ligado');

    await advance(10_000);
    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));
    expect(streams[0]!.closed).toBe(true);
  });

  for (const [status, why] of [
    [403, 'without DINING_VIEW or PRODUCTION_VIEW'],
    [401, 'refused'],
  ] as const) {
    it(`a ${status} (${why}) stops reconnecting, says "Atualizações indisponíveis" and keeps polling every 15 s`, async () => {
      fakeTimers();
      const { calls, requests } = await onTheMap(() => problem(status, `HTTP_${status}`));

      await waitFor(() => expect(connection()).toHaveTextContent('Atualizações indisponíveis'));
      const before = requests.length;
      await advance(30_000);

      expect(calls.count).toBe(1);
      expect(requests).toHaveLength(before + 2);
      expect(connection()).toHaveTextContent('Atualizações indisponíveis');
    });
  }

  it('shows "Sem ligação" when the first subscription fails', async () => {
    await onTheMap(() => HttpResponse.error());

    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));
  });

  it('the session end closes the stream, and no new one opens', async () => {
    fakeTimers();
    posLogoutEndpoint();
    const { streams, calls, user } = await onTheMap();
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    await user.click(screen.getByRole('button', { name: 'Trocar empregado' }));
    await screen.findByLabelText('Código de empregado');

    await waitFor(() => expect(streams[0]!.closed).toBe(true));
    await advance(20_000);
    expect(calls.count).toBe(1);
  });
});
