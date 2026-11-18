import createClient, { type Middleware } from 'openapi-fetch';
import { ApiError, errorFromResponse, networkError } from './errors';
import type { paths } from './schema';

/** Turns every non-2xx answer and every network failure into a thrown ApiError; an abort passes through. */
const errorMiddleware: Middleware = {
  async onResponse({ response }) {
    if (!response.ok) throw await errorFromResponse(response);
    return undefined;
  },
  onError({ error }) {
    if (error instanceof ApiError || (error instanceof DOMException && error.name === 'AbortError')) {
      return error;
    }
    return networkError(error);
  },
};

export type ApiClientOptions = {
  /** Defaults to the page's origin: each app is same-origin with its API (ADR 0041). */
  baseUrl?: string;
  /** The app's middleware: `posMiddleware` or `backOfficeMiddleware`. */
  middleware?: Middleware[];
  /** Replaces the global `fetch`; for tests. */
  fetch?: (request: Request) => Promise<Response>;
};

/** The typed client over the committed contract (ADR 0042). Calls resolve with `data` or throw an ApiError. */
export function createApiClient({ baseUrl, middleware = [], fetch }: ApiClientOptions = {}) {
  const client = createClient<paths>({ baseUrl, fetch });
  client.use(errorMiddleware, ...middleware);
  return client;
}

export type ApiClient = ReturnType<typeof createApiClient>;
