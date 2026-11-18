import type { TerminalTokens } from './terminal-tokens';

type Fetch = (request: Request) => Promise<Response>;

/**
 * The `fetch` under the POS API client (FC §6): once the terminal is enrolled, every call goes out with a fresh
 * terminal token, and a 401 that is not `POS_SESSION_INVALID` renews the token once and repeats the call. A refused
 * call was not processed, so repeating it is safe. Without a token nothing is sent. `posMiddleware` sets the header
 * first; this replaces it with the token as it is when the request actually leaves.
 */
export function withTerminalToken(
  tokens: TerminalTokens,
  fetch: Fetch = (request) => globalThis.fetch(request),
): Fetch {
  return async (request) => {
    if (!tokens.active()) return fetch(request);
    await tokens.ensureFresh();
    const repeat = request.clone();
    const sent = tokens.accessToken();
    // No token means Keycloak is unreachable (or the terminal was just revoked): the client maps this to "Sem ligação".
    if (!sent) throw new TypeError('No terminal token');
    const response = await fetch(withBearer(request, sent));
    if (response.status !== 401 || (await codeOf(response)) === 'POS_SESSION_INVALID') return response;
    await tokens.renew();
    const renewed = tokens.accessToken();
    if (!renewed || renewed === sent) return response;
    return fetch(withBearer(repeat, renewed));
  };
}

function withBearer(request: Request, token: string | undefined): Request {
  if (token) request.headers.set('Authorization', `Bearer ${token}`);
  return request;
}

async function codeOf(response: Response): Promise<string | undefined> {
  try {
    const body: unknown = await response.clone().json();
    const code = typeof body === 'object' && body !== null ? (body as Record<string, unknown>).code : undefined;
    return typeof code === 'string' ? code : undefined;
  } catch {
    return undefined;
  }
}
