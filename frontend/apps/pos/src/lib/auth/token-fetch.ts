import type { TerminalTokens } from './terminal-tokens';

type Fetch = (request: Request) => Promise<Response>;

/** The one POS call made without a terminal token: the enrolment, whose code is its credential (D182). */
function isEnrolment(request: Request): boolean {
  return request.method === 'POST' && new URL(request.url).pathname === '/api/v1/pos/enrolment';
}

/**
 * The `fetch` under the POS API client (FC §6): every call but `POST /api/v1/pos/enrolment` goes out with a fresh
 * terminal token, and a 401 the security chain answered (it carries a `WWW-Authenticate` challenge) renews the token
 * once and repeats the call: that call was refused before any handler ran, so repeating it is safe. A 401 without a
 * challenge came from a handler (a wrong PIN, D26) and is never repeated, or one wrong PIN would count twice towards
 * the lock; nor is `POS_SESSION_INVALID`, which is the employee's session, not the terminal. Without a token nothing
 * is sent. `posMiddleware` sets the header first; this replaces it with the token as it is when the request leaves.
 */
export function withTerminalToken(
  tokens: TerminalTokens,
  fetch: Fetch = (request) => globalThis.fetch(request),
): Fetch {
  return async (request) => {
    if (isEnrolment(request)) return fetch(request);
    // Not enrolled, or revoked: nothing is sent without a bearer; the client maps this to "Sem ligação".
    if (!tokens.active()) throw new TypeError('No enrolled terminal');
    await tokens.ensureFresh();
    const repeat = request.clone();
    const sent = tokens.accessToken();
    // No token means Keycloak is unreachable (or the terminal was just revoked): the client maps this to "Sem ligação".
    if (!sent) throw new TypeError('No terminal token');
    const response = await fetch(withBearer(request, sent));
    if (!isTokenRefusal(response) || (await codeOf(response)) === 'POS_SESSION_INVALID') return response;
    await tokens.renew();
    const renewed = tokens.accessToken();
    if (!renewed || renewed === sent) return response;
    return fetch(withBearer(repeat, renewed));
  };
}

function isTokenRefusal(response: Response): boolean {
  return response.status === 401 && response.headers.has('WWW-Authenticate');
}

function withBearer(request: Request, token: string | undefined): Request {
  if (token) request.headers.set('Authorization', `Bearer ${token}`);
  return request;
}

export async function codeOf(response: Response): Promise<string | undefined> {
  try {
    const body: unknown = await response.clone().json();
    const code = typeof body === 'object' && body !== null ? (body as Record<string, unknown>).code : undefined;
    return typeof code === 'string' ? code : undefined;
  } catch {
    return undefined;
  }
}
