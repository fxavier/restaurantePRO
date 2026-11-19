import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The BackOffice is same-origin with the BFF (ADR 0041). In development Vite forwards the BFF's paths and keeps the
// browser's Host (changeOrigin false), so the BFF's __Host- cookies and OAuth2 redirects stay on this origin (FC §3).
// The BFF listens on HTTPS with a development certificate, hence `secure: false`.
const bff = process.env.BFF_URL ?? 'https://localhost:8443';
const toBff = { target: bff, changeOrigin: false, secure: false };

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5174,
    strictPort: true,
    proxy: {
      '/api': toBff,
      '/bff': toBff,
      '/oauth2': toBff,
      '/login': toBff,
    },
  },
  preview: { port: 4174, strictPort: true },
  test: {
    environment: 'jsdom',
    testTimeout: 60_000, // FE-F30: see src/test-setup.ts
    setupFiles: ['./src/test-setup.ts'],
  },
});
