import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { goToLogin } from './browser';
import { isSessionExpired, isUnavailable } from './errors';

/** Any call answered with an expired session sends the user to the login (FC §6, §9). */
function onError(error: unknown) {
  if (isSessionExpired(error)) goToLogin();
}

/**
 * BackOffice defaults (FC §5): data is fresh for 30 s; a query retries twice when the service is unreachable and never
 * on an answer; a mutation never retries (FC §8). `retryDelay` is for tests.
 */
export function createQueryClient({ retryDelay }: { retryDelay?: number } = {}) {
  return new QueryClient({
    queryCache: new QueryCache({ onError }),
    mutationCache: new MutationCache({ onError }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => isUnavailable(error) && failureCount < 2,
        ...(retryDelay === undefined ? {} : { retryDelay }),
      },
      mutations: { retry: false },
    },
  });
}
