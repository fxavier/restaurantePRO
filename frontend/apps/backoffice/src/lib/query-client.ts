import { focusManager, MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { goToLogin } from './browser';
import { isSessionExpired, isTenantChanged, isUnavailable } from './errors';

/** The signed-in user's `SessionView`; its `tenantId` is the company this tab shows (FE-F08, FE-F16). */
export const SESSION_QUERY_KEY = ['auth', 'session'] as const;

/**
 * "Focus" means the window has focus and the tab is visible. TanStack listens to `visibilitychange` only; switching
 * back from another window of the same browser fires only `blur`/`focus`, so those count too. A refetch on focus needs
 * the unfocused state first, hence `blur`.
 */
focusManager.setEventListener((setFocused) => {
  const onVisibility = () => setFocused(document.visibilityState === 'visible');
  const onFocus = () => setFocused(true);
  const onBlur = () => setFocused(false);
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('focus', onFocus);
  window.addEventListener('blur', onBlur);
  return () => {
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('focus', onFocus);
    window.removeEventListener('blur', onBlur);
  };
});

/**
 * BackOffice defaults (FC §5): data is fresh for 30 s; a query retries twice when the service is unreachable and never
 * on an answer; a mutation never retries (FC §8). `retryDelay` is for tests.
 */
export function createQueryClient({ retryDelay }: { retryDelay?: number } = {}) {
  /**
   * An expired session sends the user to the login (FC §6, §9). A write refused because another tab switched the
   * company rereads the session, so the label follows the server; the write itself is not repeated (FE-F16).
   */
  const onError = (error: unknown) => {
    if (isSessionExpired(error)) goToLogin();
    if (isTenantChanged(error)) void client.invalidateQueries({ queryKey: SESSION_QUERY_KEY });
  };
  const client: QueryClient = new QueryClient({
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
  return client;
}
