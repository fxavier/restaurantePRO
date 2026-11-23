import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.ts'],
    // Only with `vitest run --coverage`: advisory, no thresholds (not a gate).
    coverage: {
      provider: 'v8',
      include: ['src/**'],
      reporter: ['text-summary', 'lcov', 'json-summary'],
      reportOnFailure: true, // a test slowed by the instrumentation must not cost the report
    },
  },
});
