import createClient, { type Middleware } from 'openapi-fetch';
import { ApiError, errorFromResponse, networkError } from './errors';
import type { paths } from './schema';

/** The default time a request may take for its whole answer (FC §8). */
export const DEFAULT_TIMEOUT_MS = 15_000;

/**
 * Turns every non-2xx answer and every network failure into a thrown ApiError. A timeout is a network failure (the
 * outcome is unknown); the caller's own abort passes through unchanged.
 */
const errorMiddleware: Middleware = {
  async onResponse({ response }) {
    if (!response.ok) throw await errorFromResponse(response);
    return undefined;
  },
  onError({ error }) {
    if (error instanceof ApiError || (error instanceof Error && error.name === 'AbortError')) {
      return error;
    }
    return networkError(error);
  },
};

const timeoutOverrides = new WeakMap<Request, number>();

/** A per-call timeout: `client.GET(path, { middleware: [requestTimeout(60_000)] })` (per-call middleware runs last). */
export function requestTimeout(milliseconds: number): Middleware {
  return {
    onRequest({ request }) {
      timeoutOverrides.set(request, milliseconds);
      return undefined;
    },
  };
}

/**
 * Aborts a request whose whole answer has not arrived within its timeout, with a `TimeoutError` (mapped to NETWORK),
 * combined with any signal of the caller. The body is read here, under the same clock, so a stalled body times out
 * too; only `text/event-stream` (the POS notification stream) is timed until it starts, then left open.
 */
function withTimeout(fetch: (request: Request) => Promise<Response>, defaultMs: number) {
  return async (request: Request) => {
    const timeout = new AbortController();
    const timer = setTimeout(
      () => timeout.abort(new DOMException('The request timed out.', 'TimeoutError')),
      timeoutOverrides.get(request) ?? defaultMs,
    );
    try {
      const response = await fetch(new Request(request, { signal: AbortSignal.any([request.signal, timeout.signal]) }));
      if (response.body === null || isEventStream(response)) return response;
      const body = await Promise.race([response.arrayBuffer(), rejectOnAbort(timeout.signal)]);
      return new Response(body, response);
    } finally {
      clearTimeout(timer);
    }
  };
}

function isEventStream(response: Response): boolean {
  return response.headers.get('Content-Type')?.toLowerCase().startsWith('text/event-stream') ?? false;
}

/** Rejects with the abort reason, whatever the fetch implementation does with its body on abort. */
function rejectOnAbort(signal: AbortSignal): Promise<never> {
  return new Promise((_, reject) => {
    if (signal.aborted) reject(signal.reason);
    signal.addEventListener('abort', () => reject(signal.reason), { once: true });
  });
}

export type ApiClientOptions = {
  /** Defaults to the page's origin: each app is same-origin with its API (ADR 0041). */
  baseUrl?: string;
  /** The app's middleware: `posMiddleware` or `backOfficeMiddleware`. */
  middleware?: Middleware[];
  /** Replaces the global `fetch`: the POS's terminal-token wrapper, or a test's stand-in. */
  fetch?: (request: Request) => Promise<Response>;
  /** Milliseconds before a request without its whole answer fails as NETWORK; 15 s by default, `requestTimeout` per call. */
  timeoutMs?: number;
};

/** The typed client over the committed contract (ADR 0042). Calls resolve with `data` or throw an ApiError. */
export function createApiClient({
  baseUrl,
  middleware = [],
  fetch = (request) => globalThis.fetch(request),
  timeoutMs = DEFAULT_TIMEOUT_MS,
}: ApiClientOptions = {}) {
  const client = createClient<paths>({ baseUrl, fetch: withTimeout(fetch, timeoutMs) });
  client.use(errorMiddleware, ...middleware);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
