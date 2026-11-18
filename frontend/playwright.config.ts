import { defineConfig, devices } from '@playwright/test';

// Smoke tests run against the built apps served by `vite preview`. Issue 05 points them at the real origins of the
// compose stack instead, through E2E_POS_URL and E2E_BACKOFFICE_URL (ADR 0043); no preview server is started then.
const posUrl = process.env.E2E_POS_URL ?? 'http://localhost:4173';
const backOfficeUrl = process.env.E2E_BACKOFFICE_URL ?? 'http://localhost:4174';
const preview = !process.env.E2E_POS_URL && !process.env.E2E_BACKOFFICE_URL;

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { trace: 'retain-on-failure' },
  projects: [
    {
      name: 'pos',
      testDir: './e2e/pos',
      use: { ...devices['Desktop Chrome'], baseURL: posUrl, viewport: { width: 1024, height: 768 } },
    },
    {
      name: 'backoffice',
      testDir: './e2e/backoffice',
      use: { ...devices['Desktop Chrome'], baseURL: backOfficeUrl, viewport: { width: 1280, height: 800 } },
    },
  ],
  webServer: preview
    ? [
        {
          command: 'pnpm --filter pos build && pnpm --filter pos preview',
          url: posUrl,
          reuseExistingServer: !process.env.CI,
        },
        {
          command: 'pnpm --filter backoffice build && pnpm --filter backoffice preview',
          url: backOfficeUrl,
          reuseExistingServer: !process.env.CI,
        },
      ]
    : undefined,
});
