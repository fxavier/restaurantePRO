import type { PosSession } from './pos-session';
import { codeOf } from './token-fetch';

type Fetch = (request: Request) => Promise<Response>;

/**
 * Ends the POS session when the backend answers 401 `POS_SESSION_INVALID` (FC §9: back to the PIN pad). Only for the
 * session the request carried: a late answer for the previous employee does not end the next one's.
 */
export function endingInvalidSession(session: PosSession, fetch: Fetch): Fetch {
  return async (request) => {
    const sent = request.headers.get('X-Pos-Session');
    const response = await fetch(request);
    if (
      response.status === 401 &&
      sent !== null &&
      sent === session.token() &&
      (await codeOf(response)) === 'POS_SESSION_INVALID'
    ) {
      session.end('invalid');
    }
    return response;
  };
}
