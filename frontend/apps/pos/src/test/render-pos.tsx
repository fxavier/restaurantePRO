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
   * identity); the test sets up the token endpoint.
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
    await waitFor(() => expect(services.tokens.status()).toBe('ready'));
    act(() => services.session.start(SESSION));
  }
  return { devices, services, router, user, queryClient };
}
