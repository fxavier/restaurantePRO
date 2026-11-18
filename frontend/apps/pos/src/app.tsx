import { QueryClientProvider } from '@tanstack/react-query';
import { type ComponentProps, useState } from 'react';
import { RouterProvider } from 'react-router';
import { createQueryClient } from './lib/query-client';
import { type PosServices, ServicesProvider } from './lib/services';

type Props = { services: PosServices; router: ComponentProps<typeof RouterProvider>['router'] };

export function PosApp({ services, router }: Props) {
  const [queryClient] = useState(createQueryClient);
  return (
    <ServicesProvider services={services}>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ServicesProvider>
  );
}
