import { focusManager, MutationCache, QueryCache, QueryClient } from '@tanstack/react-query';
import { goToLogin, reloadForTenantChange } from './browser';
import { isSessionExpired, isTenantChanged, isUnavailable } from './errors';

/**
 * An expired session sends the user to the login (FC §6, §9). An answer from another company than the page was
 * loaded in, or a write the BFF refused for it, reloads the page (FE-F16).
 */
function onError(error: unknown) {
  if (isSessionExpired(error)) goToLogin();
  if (isTenantChanged(error)) reloadForTenantChange();
}

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
