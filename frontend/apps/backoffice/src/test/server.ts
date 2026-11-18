import { setupServer } from 'msw/node';

/** The network edge of the app-level tests (FC §14); each test adds its handlers with `server.use`. */
export const server = setupServer();
