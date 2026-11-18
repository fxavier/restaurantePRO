import { act, screen, waitFor } from '@testing-library/react';
import { HttpResponse } from 'msw';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  FIRST_HEARTBEAT_MS,
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

/**
 * Advances fake time in 250 ms steps until `check` passes (at most 20 s): a slow runner takes more steps, never fails.
 * The exact delays are asserted in change-stream.test.ts under pure fake time.
 */
async function advanceUntil(check: () => void) {
  for (let waited = 0; ; waited += 250) {
    try {
      check();
      return;
    } catch (error) {
      if (waited >= 20_000) throw error;
      await advance(250);
    }
  }
}

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
    // The exact 15 s and the reconnect backoff are timed in change-stream.test.ts; here, that polling happens.
    const before = requests.length;
    await advance(15_000);
    await waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(before + 1));
    await advance(15_000);
    await waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(before + 2));
  });

  it('on reconnect reads every POS query again, shows "Ligado" and stops polling', async () => {
    fakeTimers();
    const { streams, requests, show } = await onTheMap('stream', () => HttpResponse.error(), 'stream');
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
    streams[0]!.drop();
    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));
    const before = requests.length;
    show(M1_OCCUPIED);

    // The first retry fails; the second opens the stream.
    await advanceUntil(() => expect(connection()).toHaveTextContent('Ligado'));

    expect(streams).toHaveLength(2);
    await waitFor(() => expect(m1()).toHaveAccessibleName(/^Mesa M1 Ocupada/));
    expect(requests).toHaveLength(before + 1);
    await advance(30_000);
    expect(requests).toHaveLength(before + 1); // no polling once open
  });

  it('a stream the server completes (its 30-minute timeout) is opened again', async () => {
    fakeTimers();
    const { streams } = await onTheMap();
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));

    streams[0]!.end();

    await advanceUntil(() => expect(streams).toHaveLength(2));
    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
  });

  it('a stream whose answer starts only with the first heartbeat (25 s, as Spring writes it) is waited for', async () => {
    fakeTimers();
    const { calls } = await onTheMap('late-stream');
    expect(connection()).toHaveTextContent('A ligar');

    await advance(FIRST_HEARTBEAT_MS - 1_000);
    expect(connection()).toHaveTextContent('A ligar');
    expect(calls.count).toBe(1);

    await advanceUntil(() => expect(connection()).toHaveTextContent('Ligado'));
    expect(calls.count).toBe(1);
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

      await waitFor(() => expect(requests.length).toBeGreaterThanOrEqual(before + 2));
      expect(calls.count).toBe(1);
      expect(connection()).toHaveTextContent('Atualizações indisponíveis');
    });
  }

  it('a 401 with a token challenge (the terminal token refused) is retried with backoff, not taken as final', async () => {
    fakeTimers();
    const challenged = () => problem(401, 'HTTP_401', { 'WWW-Authenticate': 'Bearer error="invalid_token"' });
    const { calls } = await onTheMap(challenged, 'stream');

    await waitFor(() => expect(connection()).toHaveTextContent('Sem ligação'));

    await advanceUntil(() => expect(connection()).toHaveTextContent('Ligado'));
    expect(calls.count).toBe(2);
  });

  it('POS_SESSION_INVALID on the stream ends the session', async () => {
    tokenEndpoint(token('jwt-1'));
    tablesEndpoint(() => HttpResponse.json(MAP));
    const { calls } = notificationsEndpoint(() => problem(401, 'POS_SESSION_INVALID'));
    await renderPos({ device: 'enrolled', loggedIn: true, path: '/tables' });

    await waitFor(() => expect(calls.count).toBe(1));
    expect(await screen.findByLabelText('Código de empregado')).toBeInTheDocument();
  });

  it('a refusal lasts one session: after logout and a new login the stream is tried again', async () => {
    fakeTimers();
    posLogoutEndpoint();
    const { services, user, calls } = await onTheMap(() => problem(403, 'HTTP_403'), 'stream');
    await waitFor(() => expect(connection()).toHaveTextContent('Atualizações indisponíveis'));

    await user.click(screen.getByRole('button', { name: 'Trocar empregado' }));
    await screen.findByLabelText('Código de empregado');
    act(() => services.session.start(SESSION));

    await waitFor(() => expect(connection()).toHaveTextContent('Ligado'));
    expect(calls.count).toBe(2);
  });

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
