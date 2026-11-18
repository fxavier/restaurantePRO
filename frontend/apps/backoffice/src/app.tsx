import type { ApiClient } from '@restaurantpro/api-client';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider, type createBrowserRouter } from 'react-router';
import { ApiProvider } from './lib/api';
import { TenantPinProvider, type TenantPin } from './lib/tenant-pin';

type AppProps = {
  router: ReturnType<typeof createBrowserRouter>;
  api: ApiClient;
  queryClient: QueryClient;
  tenantPin: TenantPin;
};

/** The BackOffice with its providers; `main.tsx` passes the browser's, tests pass their own. */
export function App({ router, api, queryClient, tenantPin }: AppProps) {
  return (
    <ApiProvider client={api}>
      <TenantPinProvider pin={tenantPin}>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </TenantPinProvider>
    </ApiProvider>
  );
}
