import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The POS is same-origin with the backend (ADR 0041): in development Vite stands in for the reverse proxy and
// forwards only the two API prefixes the POS origin serves (FC §3).
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

// A fixed terminal zone, deliberately not the test store's (Africa/Maputo, src/test/server.ts): a time shown in the
// terminal's zone instead of the store's fails the tests (FC §12). Set here as well as in `test.env`: worker threads
// share this process's zone, and only forks read `test.env.TZ`.
const TEST_TIME_ZONE = 'America/Sao_Paulo';
if (process.env.VITEST) process.env.TZ = TEST_TIME_ZONE;

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api/v1/pos': { target: backend },
      '/api/v1/auth': { target: backend },
    },
  },
  preview: { port: 4173, strictPort: true },
  test: {
    environment: 'jsdom',
    testTimeout: 30_000, // FE-F30: see src/test-setup.ts
    setupFiles: ['./src/test-setup.ts'],
    env: { TZ: TEST_TIME_ZONE },
  },
});
