import { useCallback } from 'react';
import { useServices } from '../services';
import type { EndReason } from './pos-session';

/**
 * Logs the employee out (FC §6 step 4): the session ends here at once, so the PIN pad shows without waiting, and
 * `POST /api/v1/auth/pos-logout` is sent with the token it had. That is the one call whose `X-Pos-Session` is not the
 * current session's (there is none any more), so it is set here rather than by `posMiddleware`. If the call fails,
 * the server's own idle expiry ends the session. Nothing is lost: draft lines are rows on the order.
 */
export function useEndSession() {
  const { api, session } = useServices();
  return useCallback(
    (reason: Exclude<EndReason, 'invalid'>) => {
      const token = session.token();
      if (!token) return;
      session.end(reason);
      api.POST('/api/v1/auth/pos-logout', { headers: { 'X-Pos-Session': token } }).catch(() => undefined);
    },
    [api, session],
  );
}
