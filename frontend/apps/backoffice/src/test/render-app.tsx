import { backOfficeMiddleware, createApiClient } from '@restaurantpro/api-client';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { App } from '../app';
import { browser } from '../lib/browser';
import { createQueryClient } from '../lib/query-client';
import { routes } from '../routes';
import { ORIGIN } from './fixtures';

export const CSRF_TOKEN = 'csrf-token-1';

/**
 * The whole BackOffice as the browser runs it (FC §14 seam 1): routes, query client, the real API client with the
 * BackOffice middleware, and MSW at the network edge. Top-level navigations (login, logout) are recorded, not run.
 */
export function renderApp(url = '/') {
  const leave = vi.spyOn(browser, 'assign').mockImplementation(() => undefined);
  const router = createMemoryRouter(routes, { initialEntries: [url] });
  const api = createApiClient({
    baseUrl: ORIGIN,
    middleware: [backOfficeMiddleware(() => `__Host-XSRF-TOKEN=${CSRF_TOKEN}`)],
  });
  const queryClient = createQueryClient({ retryDelay: 0 });
  const user = userEvent.setup();
  const view = render(<App router={router} api={api} queryClient={queryClient} />);
  const currentUrl = () => `${router.state.location.pathname}${router.state.location.search}`;
  return { ...view, user, router, leave, currentUrl };
}
