import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The POS is same-origin with the backend (ADR 0041): in development Vite stands in for the reverse proxy and
// forwards only the two API prefixes the POS origin serves (FC §3).
const backend = process.env.BACKEND_URL ?? 'http://localhost:8080';

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
    setupFiles: ['./src/test-setup.ts'],
    // A fixed zone, so times on the table map are deterministic: the store's zone is not in the POS contract yet.
    env: { TZ: 'Africa/Maputo' },
  },
});
