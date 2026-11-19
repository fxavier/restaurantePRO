import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation } from '@tanstack/react-query';
import { browser, goToLogin } from '../../../lib/browser';
import { useApi } from '../../../lib/api';

type BffLogoutRoute = {
  POST(path: '/bff/logout', init: { headers: { Accept: string } }): Promise<{ data?: { logoutUrl?: string } }>;
};

/**
 * `POST /bff/logout` with the CSRF header (FC §6). It is a `fetch`, not a form: the BFF's `csrf.spa()` takes the raw
 * token only from the header, and a form cannot send one. A fetch cannot follow the BFF's cross-origin redirect to
 * Keycloak, so it asks for JSON: the BFF ends its session and Keycloak's over the back channel, then answers the
 * end-session URL (no token in it), and the browser goes there top-level so Keycloak clears its own cookies (FE-F10).
 */
export function useLogout() {
  const api = useApi();
  return useMutation({
    mutationFn: async () => {
      const { data } = await (api as ApiClient & BffLogoutRoute).POST('/bff/logout', {
        headers: { Accept: 'application/json' },
      });
      return data?.logoutUrl;
    },
    onSuccess: (logoutUrl) => (logoutUrl ? browser.assign(logoutUrl) : goToLogin()),
  });
}
