import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter } from 'react-router';
import { vi } from 'vitest';
import { App } from '../app';
import { createBackOfficeClient } from '../lib/api';
import { browser } from '../lib/browser';
import { createQueryClient } from '../lib/query-client';
import { createTenantPin } from '../lib/tenant-pin';
import { routes } from '../routes';
import { ORIGIN } from './fixtures';

export const CSRF_TOKEN = 'csrf-token-1';

/**
 * The whole BackOffice as the browser runs it (FC §14 seam 1): routes, query client, the real API client with the
 * BackOffice middleware wired as `main.tsx` wires it, and MSW at the network edge. Top-level navigations (login,
 * logout) are recorded, not run.
 */
export function renderApp(url = '/') {
  const leave = vi.spyOn(browser, 'assign').mockImplementation(() => undefined);
  const router = createMemoryRouter(routes, { initialEntries: [url] });
  const queryClient = createQueryClient({ retryDelay: 0 });
  const tenantPin = createTenantPin();
  const api = createBackOfficeClient(tenantPin, { baseUrl: ORIGIN, cookies: () => `__Host-XSRF-TOKEN=${CSRF_TOKEN}` });
  const user = userEvent.setup();
  const view = render(<App router={router} api={api} queryClient={queryClient} tenantPin={tenantPin} />);
  const currentUrl = () => `${router.state.location.pathname}${router.state.location.search}`;
  return { ...view, user, router, leave, currentUrl, api, queryClient, tenantPin };
}
