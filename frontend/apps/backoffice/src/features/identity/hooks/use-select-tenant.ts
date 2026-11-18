import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { useApi } from '../../../lib/api';
import { useTenantPin } from '../../../lib/tenant-pin';

/**
 * `PUT /bff/tenant` is the BFF's own route, outside the backend's OpenAPI contract. It goes through the same client, so
 * it gets the CSRF header, the session cookie and ApiError mapping like every other call.
 */
type BffTenantRoute = { PUT(path: '/bff/tenant', init: { body: { tenantId: string } }): Promise<unknown> };

/**
 * Selects the company in the BFF session (D177, FC §6), then forgets everything loaded for the previous one: every
 * query is reset and refetched (the session too, which then names the new company), and the store selection is
 * dropped, since stores belong to a company (story 6).
 */
export function useSelectTenant() {
  const api = useApi();
  const queryClient = useQueryClient();
  const pin = useTenantPin();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  return useMutation({
    mutationFn: async (tenantId: string) => {
      await (api as ApiClient & BffTenantRoute).PUT('/bff/tenant', { body: { tenantId } });
    },
    onSuccess: async (_, tenantId) => {
      pin.set(tenantId); // this tab's own switch: its data is about to be reloaded for the new company (FE-F16)
      const reset = queryClient.resetQueries();
      navigate(pathname);
      await reset;
    },
  });
}
