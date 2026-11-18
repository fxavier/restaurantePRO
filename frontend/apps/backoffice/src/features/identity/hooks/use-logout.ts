import { ApiError, type ApiClient } from '@restaurantpro/api-client';
import { useMutation } from '@tanstack/react-query';
import { goToLogin } from '../../../lib/browser';
import { useApi } from '../../../lib/api';

type BffLogoutRoute = { POST(path: '/bff/logout', init: { redirect: RequestRedirect }): Promise<unknown> };

/**
 * The BFF answers a logout with a redirect to Keycloak's end-session page. Asked with `redirect: 'manual'`, the browser
 * returns it as an opaque redirect (status 0, but not a network failure) and Node returns the 3xx itself; the client's
 * error middleware throws both. Either one means the BFF session has ended.
 */
function isRedirect(error: unknown) {
  return (
    error instanceof ApiError &&
    error.code !== 'NETWORK' &&
    (error.status === 0 || (error.status >= 300 && error.status < 400))
  );
}

/**
 * `POST /bff/logout` with the CSRF header (FC §6). It is a `fetch`, not a form: the BFF's `csrf.spa()` takes the raw
 * token only from the header, and a form cannot send one. Then the browser goes to the login.
 */
export function useLogout() {
  const api = useApi();
  return useMutation({
    mutationFn: async () => {
      try {
        await (api as ApiClient & BffLogoutRoute).POST('/bff/logout', { redirect: 'manual' });
      } catch (error) {
        if (!isRedirect(error)) throw error;
      }
    },
    onSuccess: goToLogin,
  });
}
