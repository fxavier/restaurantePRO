import type { ApiClient } from '@restaurantpro/api-client';
import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { RouterProvider, type createBrowserRouter } from 'react-router';
import { ApiProvider } from './lib/api';

type AppProps = {
  router: ReturnType<typeof createBrowserRouter>;
  api: ApiClient;
  queryClient: QueryClient;
};

/** The BackOffice with its providers; `main.tsx` passes the browser's, tests pass their own. */
export function App({ router, api, queryClient }: AppProps) {
  return (
    <ApiProvider client={api}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ApiProvider>
  );
}
