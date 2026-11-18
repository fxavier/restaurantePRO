import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, createApiClient, requestTimeout } from './index';

const BASE = 'http://api.test';

/** A server that accepts the request and never answers; like a real fetch, it rejects when the signal aborts. */
function silentServer() {
  const requests: Request[] = [];
  const fetch = (request: Request) => {
    requests.push(request);
    return new Promise<Response>((_, reject) => {
      request.signal.addEventListener('abort', () => reject(request.signal.reason), { once: true });
    });
  };
  return { fetch, requests };
}

/** Settles the call as a value, so a pending call can be checked without an unhandled rejection. */
function settle(promise: Promise<unknown>) {
  const outcome: { error?: unknown; done: boolean } = { done: false };
  promise.then(
    () => (outcome.done = true),
    (error) => Object.assign(outcome, { error, done: true }),
  );
  return outcome;
}

describe('request timeout', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('gives up after 15 s by default, as an unknown outcome: status 0, code NETWORK', async () => {
    const { fetch } = silentServer();
    const call = settle(createApiClient({ baseUrl: BASE, fetch }).GET('/api/v1/auth/session'));

    await vi.advanceTimersByTimeAsync(14_999);
    expect(call.done).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    expect(call.error).toBeInstanceOf(ApiError);
    expect(call.error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('takes another default from the client options', async () => {
    const { fetch } = silentServer();
    const call = settle(createApiClient({ baseUrl: BASE, fetch, timeoutMs: 1_000 }).GET('/api/v1/auth/session'));

    await vi.advanceTimersByTimeAsync(1_000);

    expect(call.error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('is overridable per call', async () => {
    const { fetch } = silentServer();
    const client = createApiClient({ baseUrl: BASE, fetch });
    const call = settle(client.GET('/api/v1/auth/session', { middleware: [requestTimeout(30_000)] }));

    await vi.advanceTimersByTimeAsync(15_000);
    expect(call.done).toBe(false);

    await vi.advanceTimersByTimeAsync(15_000);
    expect(call.error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('still applies when the caller passes its own signal', async () => {
    const { fetch } = silentServer();
    const caller = new AbortController();
    const call = settle(createApiClient({ baseUrl: BASE, fetch }).GET('/api/v1/auth/session', { signal: caller.signal }));

    await vi.advanceTimersByTimeAsync(15_000);

    expect(call.error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it("lets the caller's abort through unchanged, so a cancelled query is not \"Sem ligação\"", async () => {
    const { fetch } = silentServer();
    const caller = new AbortController();
    const call = settle(createApiClient({ baseUrl: BASE, fetch }).GET('/api/v1/auth/session', { signal: caller.signal }));

    caller.abort();
    await vi.advanceTimersByTimeAsync(0);

    expect(call.error).not.toBeInstanceOf(ApiError);
    expect(call.error).toMatchObject({ name: 'AbortError' });
  });

  it('covers the whole answer: a body that stalls after a first chunk fails as NETWORK', async () => {
    const fetch = async () =>
      new Response(
        new ReadableStream<Uint8Array>({
          start: (controller) => controller.enqueue(new TextEncoder().encode('{"name":')),
          pull: () => new Promise(() => {}),
        }),
        { headers: { 'Content-Type': 'application/json' } },
      );
    const call = settle(createApiClient({ baseUrl: BASE, fetch }).GET('/api/v1/auth/session'));

    await vi.advanceTimersByTimeAsync(14_999);
    expect(call.done).toBe(false);

    await vi.advanceTimersByTimeAsync(1);
    expect(call.error).toMatchObject({ status: 0, code: 'NETWORK' });
  });

  it('keeps status and headers of a buffered answer, and a 204 without a body', async () => {
    const client = createApiClient({ baseUrl: BASE,
      fetch: async () => Response.json({ name: 'Ana' }, { headers: { 'X-Test': 'kept' } }),
    });
    const empty = createApiClient({ baseUrl: BASE, fetch: async () => new Response(null, { status: 204 }) });

    const { data, response } = await client.GET('/api/v1/auth/session');
    const none = await empty.GET('/api/v1/auth/session');

    expect(data).toEqual({ name: 'Ana' });
    expect(response.headers.get('X-Test')).toBe('kept');
    expect(none.response.status).toBe(204);
  });

  it('times the SSE stream only until it starts, so the stream is not cut', async () => {
    let signal: AbortSignal | undefined;
    const fetch = async (request: Request) => {
      signal = request.signal;
      return new Response(new ReadableStream({ pull: () => new Promise(() => {}) }), {
        headers: { 'Content-Type': 'text/event-stream;charset=UTF-8' },
      });
    };
    await createApiClient({ baseUrl: BASE, fetch }).GET('/api/v1/auth/session', { parseAs: 'stream' });

    await vi.advanceTimersByTimeAsync(60_000);

    expect(signal?.aborted).toBe(false);
  });
});
