import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useLocation, useNavigate } from 'react-router';
import { useApi } from '../../../lib/api';
import { rememberTenant } from '../selected-tenant';

/**
 * `PUT /bff/tenant` is the BFF's own route, outside the backend's OpenAPI contract. It goes through the same client, so
 * it gets the CSRF header, the session cookie and ApiError mapping like every other call.
 */
type BffTenantRoute = { PUT(path: '/bff/tenant', init: { body: { tenantId: string } }): Promise<unknown> };

/**
 * Selects the company in the BFF session (D177, FC §6), then forgets everything loaded for the previous one: every
 * query is reset and refetched, and the store selection is dropped, since stores belong to a company (story 6).
 */
export function useSelectTenant() {
  const api = useApi();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  return useMutation({
    mutationFn: async (tenantId: string) => {
      await (api as ApiClient & BffTenantRoute).PUT('/bff/tenant', { body: { tenantId } });
    },
    onSuccess: async (_, tenantId) => {
      rememberTenant(tenantId);
      const reset = queryClient.resetQueries();
      navigate(pathname);
      await reset;
    },
  });
}
