import { defineConfig, devices } from '@playwright/test';

// Two modes.
// - Preview (default): the smoke tests against the built apps served by `vite preview`.
// - Real stack (ADR 0043): E2E_POS_URL and E2E_BACKOFFICE_URL point at the origins of the compose stack
//   (deploy/e2e/stack.sh up; `stack.sh test` sets them). No preview server is started; the global setup seeds this
//   run's tenant, administrator, waiter and catalog, and the `stack` project runs the specs that need the backend.
const posUrl = process.env.E2E_POS_URL ?? 'http://localhost:4173';
const backOfficeUrl = process.env.E2E_BACKOFFICE_URL ?? 'http://localhost:4174';
const realStack = !!process.env.E2E_POS_URL && !!process.env.E2E_BACKOFFICE_URL;
const preview = !process.env.E2E_POS_URL && !process.env.E2E_BACKOFFICE_URL;

export default defineConfig({
  testDir: './e2e',
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: { trace: 'retain-on-failure' },
  globalSetup: realStack ? './e2e/global-setup.ts' : undefined,
  projects: [
    // node-only helpers of the e2e (no browser)
    { name: 'support', testDir: './e2e/support' },
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
    ...(realStack
      ? [
          {
            name: 'stack',
            testDir: './e2e/stack',
            // a sign-in may wait up to 30 s for a TOTP period the global setup's sign-in did not use
            timeout: 90_000,
            // One at a time: the specs sign in as the same administrator, Keycloak refuses a TOTP code twice, and the
            // last period used is known only to the worker that used it (e2e/support/totp.ts).
            workers: 1,
            use: { ...devices['Desktop Chrome'], baseURL: backOfficeUrl, viewport: { width: 1280, height: 800 } },
          },
          {
            // What disturbs the whole stack (stopping the backend, a further BackOffice sign-in): only after every
            // other project has finished, one test at a time.
            name: 'stack-serial',
            testDir: './e2e/stack-serial',
            dependencies: ['pos', 'backoffice', 'stack'],
            fullyParallel: false,
            timeout: 240_000,
            use: { ...devices['Desktop Chrome'], baseURL: posUrl, viewport: { width: 1024, height: 768 } },
          },
        ]
      : []),
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
