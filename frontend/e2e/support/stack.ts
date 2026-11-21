import { expect, type APIResponse, type Page } from '@playwright/test';
import { freshTotp } from './totp';

/** The origins of the compose stack (deploy/e2e/stack.sh env). */
export const stack = {
  pos: () => required('E2E_POS_URL'),
  backOffice: () => required('E2E_BACKOFFICE_URL'),
  keycloak: () => required('E2E_KEYCLOAK_URL'),
  backend: () => required('E2E_BACKEND_URL'),
};

/** What the global setup seeded for this run (process.env, inherited by the workers; never written to a file). */
export const seeded = {
  adminEmail: () => required('E2E_ADMIN_EMAIL'),
  adminPassword: () => required('E2E_ADMIN_PASSWORD'),
  adminTotpSecret: () => required('E2E_ADMIN_TOTP_SECRET'),
  tenantId: () => required('E2E_TENANT_ID'),
  storeId: () => required('E2E_STORE_ID'),
  waiterCode: () => required('E2E_WAITER_CODE'),
  waiterPin: () => required('E2E_WAITER_PIN'),
  /** A POS terminal of the store, its one-use enrolment code issued by the global setup. */
  terminal: (name: 'A' | 'B' | 'C') => ({
    id: required(`E2E_TERMINAL_${name}_ID`),
    code: required(`E2E_TERMINAL_${name}_CODE`),
  }),
};

export function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is not set: start the stack (deploy/e2e/stack.sh up) and run through it`);
  return value;
}

/**
 * Signs in to the BackOffice through the BFF with the password and a TOTP code. It starts at the BFF's step-up
 * entry, which asks Keycloak for LoA 2 (acr_values=2): the plain login asks for LoA 1, the password only. Pass
 * `testInfo.retry` from a test, so a retry never resubmits a code the failed attempt used.
 */
export async function signInToBackOffice(page: Page, email: string, password: string, totpSecret: string,
  retry = 0) {
  await page.goto(`${stack.backOffice()}/bff/step-up?acr_values=2&max_age=300&return=/`);
  await page.locator('#username').fill(email);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.locator('#otp').fill(await freshTotp(totpSecret, retry));
  await page.locator('#kc-login').click();
  await page.waitForURL(`${stack.backOffice()}/`);
}

/** Keycloak asks again for what max_age expired: the password (the user name may be fixed) and the TOTP code. */
export async function reauthenticate(page: Page, retry = 0) {
  const password = page.locator('#password');
  const otp = page.locator('#otp');
  await expect(password.or(otp)).toBeVisible();
  if (await password.isVisible()) {
    const username = page.locator('#username');
    if (await username.isVisible()) await username.fill(seeded.adminEmail());
    await password.fill(seeded.adminPassword());
    await page.locator('#kc-login').click();
  }
  await otp.fill(await freshTotp(seeded.adminTotpSecret(), retry));
  await page.locator('#kc-login').click();
}

/**
 * A fresh second factor for a sensitive command (the stack's step-up age is 30 s), through the BFF's step-up as the SPA
 * does it. max_age=0 always asks again: a factor Keycloak still accepts at 29 s would be stale at the backend a moment
 * later. Keycloak's session is open, so it asks only for what expired; freshTotp waits for an unused period.
 */
export async function stepUpBackOffice(page: Page, retry = 0) {
  await page.goto(`${stack.backOffice()}/bff/step-up?acr_values=2&max_age=0&return=/`);
  if (new URL(page.url()).origin === new URL(stack.backOffice()).origin) return; // no prompt was needed
  await reauthenticate(page, retry);
  await page.waitForURL(`${stack.backOffice()}/`);
}

/** The BFF's CSRF token, as the SPA reads it: the __Host-XSRF-TOKEN cookie of the BackOffice origin. */
export async function csrfToken(page: Page): Promise<string> {
  const cookie = (await page.context().cookies(stack.backOffice())).find((c) => c.name === '__Host-XSRF-TOKEN');
  if (!cookie) throw new Error('no __Host-XSRF-TOKEN cookie on the BackOffice origin');
  return cookie.value;
}

/** A BackOffice API call through the BFF with the browser's cookies and the CSRF header; fails on a non-2xx. */
export async function backOfficeCall(page: Page, method: 'GET' | 'POST' | 'PUT', path: string,
  data?: unknown, headers: Record<string, string> = {}): Promise<APIResponse> {
  const response = await page.request.fetch(`${stack.backOffice()}${path}`, {
    method,
    data,
    headers: method === 'GET' ? headers : { 'X-XSRF-TOKEN': await csrfToken(page), ...headers },
  });
  expect(response.ok(), `${method} ${path} answered ${response.status()}: ${await response.text()}`).toBe(true);
  return response;
}
