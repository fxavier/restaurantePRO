import createClient, { type Middleware } from 'openapi-fetch';
import { ApiError, errorFromResponse, networkError } from './errors';
import type { paths } from './schema';

/** The default time a request may take before its answer starts (FC §8). */
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
 * Aborts a request whose answer has not started within its timeout, with a `TimeoutError` (mapped to NETWORK), combined
 * with any signal of the caller. The clock stops when the answer starts, so a streamed body (SSE) is not cut.
 */
function withTimeout(fetch: (request: Request) => Promise<Response>, defaultMs: number) {
  return async (request: Request) => {
    const timeout = new AbortController();
    const timer = setTimeout(
      () => timeout.abort(new DOMException('The request timed out.', 'TimeoutError')),
      timeoutOverrides.get(request) ?? defaultMs,
    );
    try {
      return await fetch(new Request(request, { signal: AbortSignal.any([request.signal, timeout.signal]) }));
    } finally {
      clearTimeout(timer);
    }
  };
}

export type ApiClientOptions = {
  /** Defaults to the page's origin: each app is same-origin with its API (ADR 0041). */
  baseUrl?: string;
  /** The app's middleware: `posMiddleware` or `backOfficeMiddleware`. */
  middleware?: Middleware[];
  /** Replaces the global `fetch`: the POS's terminal-token wrapper, or a test's stand-in. */
  fetch?: (request: Request) => Promise<Response>;
  /** Milliseconds before a request without an answer fails as NETWORK; defaults to 15 s, `requestTimeout` per call. */
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
