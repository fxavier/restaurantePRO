import { ApiError } from '@restaurantpro/api-client';
import { QueryClient } from '@tanstack/react-query';

/** FC §5: POS data is never stale; queries retry twice on a network error only; mutations never retry. */
export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 0,
        retry: (failures, error) => error instanceof ApiError && error.status === 0 && failures < 2,
      },
      mutations: { retry: false },
    },
  });
}
