import { act, render, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IDBFactory } from 'fake-indexeddb';
import { createMemoryRouter } from 'react-router';
import { expect, onTestFinished } from 'vitest';
import { PosApp } from '../app';
import { createQueryClient } from '../lib/query-client';
import { generateDeviceKey } from '../lib/auth/client-assertion';
import { createDeviceStore, type DeviceRecord } from '../lib/auth/device-store';
import { createPosServices } from '../lib/services';
import { routes } from '../routes';
import { ENROLMENT, ISSUER, SESSION } from './server';

type Options = {
  path?: string;
  /** What IndexedDB holds when the app starts: nothing, a key whose enrolment did not finish, or an enrolled device. */
  device?: 'none' | 'key-only' | 'enrolled';
  /** userEvent's options, such as `advanceTimers` under fake timers. */
  user?: Parameters<typeof userEvent.setup>[0];
  /**
   * Ana's POS session, as her PIN login answers it once the terminal has its token (the PIN pad itself is tested in
   * identity); the test sets up the token endpoint. The helper then always ends on `/tables`, where the login sends
   * the terminal, whatever `path` was: a test navigates on from there.
   */
  loggedIn?: boolean;
};

/** The whole POS over a fresh, in-memory IndexedDB, with MSW standing in for the backend and Keycloak. */
export async function renderPos({ path = '/', device = 'none', user: userOptions, loggedIn = false }: Options = {}) {
  const devices = createDeviceStore(new IDBFactory());
  if (device !== 'none') {
    const record: DeviceRecord = { keyPair: await generateDeviceKey() };
    if (device === 'enrolled') record.enrolment = ENROLMENT;
    await devices.save(record);
  }
  const services = createPosServices({ issuer: ISSUER, devices });
  onTestFinished(() => services.tokens.stop());
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup(userOptions);
  const queryClient = createQueryClient();
  render(<PosApp services={services} router={router} queryClient={queryClient} />);
  if (loggedIn) {
    // As a PIN login goes: the session guard has sent the terminal to the PIN pad, and the terminal has its token.
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/login');
      expect(services.tokens.status()).toBe('ready');
    });
    act(() => services.session.start(SESSION));
    // The PIN pad answers the login with its own redirect to the tables, which React may run only after this act
    // (its session subscription is a passive effect of a commit made outside act). Until it has, a test's first
    // navigation would be undone by it; the only way off `/login` is that redirect, so it has run once this holds.
    await waitFor(() => expect(router.state.location.pathname).toBe('/tables'));
  }
  return { devices, services, router, user, queryClient };
}
