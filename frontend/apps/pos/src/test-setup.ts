import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server, wire } from './test/server';

// FE-F30: every app test drives the whole app (fake IndexedDB, WebCrypto, MSW, the router), and a fully loaded machine
// made it miss Testing Library's 1 s findBy/waitFor budget and Vitest's 5 s test budget, though each awaited the right
// state. Budgets for a loaded machine, not delays: a passing wait still ends as soon as its state appears.
configure({ asyncUtilTimeout: 10_000 });

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }));
afterEach(() => {
  cleanup();
  server.resetHandlers();
  wire.length = 0;
  localStorage.clear();
  sessionStorage.clear();
});
afterAll(() => server.close());
