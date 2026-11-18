import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation } from '@tanstack/react-query';
import { useLocation } from 'react-router';
import { useApi } from '../../../lib/api';
import { reloadAfterOwnSwitch } from '../../../lib/browser';

/**
 * `PUT /bff/tenant` is the BFF's own route, outside the backend's OpenAPI contract. It goes through the same client, so
 * it gets the CSRF header, the session cookie and ApiError mapping like every other call.
 */
type BffTenantRoute = { PUT(path: '/bff/tenant', init: { body: { tenantId: string } }): Promise<unknown> };

/**
 * Selects the company in the BFF session (D177, FC §6), then reloads the page on the same area: nothing loaded for the
 * previous company survives, the store selection neither (stores belong to a company, story 6), and the new page is
 * pinned to the new company (FE-F16). A switch in another tab ends in the same kind of page load.
 */
export function useSelectTenant() {
  const api = useApi();
  const { pathname } = useLocation();
  return useMutation({
    mutationFn: async (tenantId: string) => {
      await (api as ApiClient & BffTenantRoute).PUT('/bff/tenant', { body: { tenantId } });
      // At once, before any reread can answer in the new company and pass for another tab's switch.
      reloadAfterOwnSwitch(pathname);
    },
  });
}
