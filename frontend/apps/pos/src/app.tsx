import { type QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { type ComponentProps, useEffect, useState } from 'react';
import { RouterProvider } from 'react-router';
import { createQueryClient } from './lib/query-client';
import { type PosServices, ServicesProvider } from './lib/services';

type Props = {
  services: PosServices;
  router: ComponentProps<typeof RouterProvider>['router'];
  /** A test's own client, to inspect its caches; the app makes one otherwise. */
  queryClient?: QueryClient;
};

export function PosApp({ services, router, queryClient: given }: Props) {
  const [queryClient] = useState(() => given ?? createQueryClient());

  // One employee's data never shows for the next: the cache goes with the session that read it.
  useEffect(
    () =>
      services.session.subscribe(() => {
        if (!services.session.token()) queryClient.clear();
      }),
    [services.session, queryClient],
  );

  return (
    <ServicesProvider services={services}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ServicesProvider>
  );
}
