import { expect, test, type Page } from '@playwright/test';
import { randomBytes } from 'node:crypto';
import { expectNoAxeViolations } from '../axe';
import { seeded, signInToBackOffice, stack } from '../support/stack';
import { freshTotp } from '../support/totp';

// Flow (a) of ADR 0043 / PRD AC2 on the real stack: OTP sign-in, the store, register a terminal, then issue its
// enrolment code with a stale second factor, through the BFF's step-up and back to "Continuar".
// The stack's backend runs with step-up-max-age PT30S (deploy/e2e/compose.yaml), so the second factor of the sign-in
// is stale 30 s later instead of five minutes.
const STEP_UP_MAX_AGE_MS = 30_000;

/** Keycloak asks again for what max_age expired: the password (the user name may be fixed) and the TOTP code. */
async function reauthenticate(page: Page, retry: number) {
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

test('flow (a): register a terminal, then issue its code through step-up, once', async ({ page }, testInfo) => {
  // two TOTP periods may be waited for (sign-in and step-up), plus the 30 s for the second factor to go stale
  test.setTimeout(180_000);
  const creates: number[] = [];
  const issues: number[] = [];
  page.on('response', (response) => {
    if (response.request().method() !== 'POST') return;
    const path = new URL(response.url()).pathname;
    if (path === '/api/v1/backoffice/terminals') creates.push(response.status());
    if (/^\/api\/v1\/backoffice\/terminals\/[^/]+\/enrollment-code$/.test(path)) issues.push(response.status());
  });
  const name = `Caixa E2E ${randomBytes(2).toString('hex')}`;
  const code = `E2E${randomBytes(3).toString('hex').toUpperCase()}`;
  const terminals = `${stack.backOffice()}/organization/terminals?storeId=${seeded.storeId()}`;

  await signInToBackOffice(page, seeded.adminEmail(), seeded.adminPassword(), seeded.adminTotpSecret(), testInfo.retry);
  const signedInAt = Date.now();

  await page.getByRole('navigation', { name: 'Áreas' }).getByRole('link', { name: 'Terminais' }).click();
  const store = page.getByRole('combobox', { name: 'Loja' });
  await expect(store).toHaveValue(seeded.storeId()); // the run's company has one store, selected on its own
  await expect(page).toHaveURL(terminals);
  await expect(page.getByText('Esta loja ainda não tem terminais.')).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Registar terminal' }).click();
  const form = page.getByRole('dialog', { name: 'Registar terminal' });
  await form.getByLabel('Código').fill(code);
  await form.getByLabel('Nome').fill(name);
  await form.getByRole('button', { name: 'Registar' }).click();
  await expect(page.getByRole('cell', { name, exact: true })).toBeVisible();
  expect(creates).toEqual([201]);

  // Let the sign-in's second factor go stale; Keycloak's auth_time is before signedInAt.
  await page.waitForTimeout(Math.max(0, signedInAt + STEP_UP_MAX_AGE_MS + 2_000 - Date.now()));

  await page.getByRole('button', { name: `Emitir código para ${name}` }).click();
  const stepUp = page.getByRole('alertdialog', { name: 'Confirme a sua identidade' });
  await expect(stepUp).toBeVisible();
  await expectNoAxeViolations(page);
  await stepUp.getByRole('button', { name: 'Confirmar identidade' }).click();

  await reauthenticate(page, testInfo.retry);
  await page.waitForURL(`${stack.backOffice()}/step-up/resume`);
  await expect(page.getByText(`Emitir o código de registo do terminal ${name}.`)).toBeVisible();
  await expectNoAxeViolations(page);
  await page.getByRole('button', { name: 'Continuar' }).click();

  const dialog = page.getByRole('dialog', { name: `Código de registo de ${name}` });
  const shown = dialog.getByText(/^[0-9A-Z]{4}( [0-9A-Z]{4}){5}$/);
  await expect(shown).toBeVisible();
  await expectNoAxeViolations(page);
  const enrolmentCode = (await shown.textContent())!.replaceAll(' ', '');

  await dialog.getByRole('button', { name: 'Fechar' }).click();
  await expect(page).toHaveURL(terminals);

  // Ran once: one terminal, one 201; the code issued by exactly one answer, after the step-up challenge.
  const rows = page.getByRole('table', { name: 'Terminais da loja' }).getByRole('row');
  await expect(rows).toHaveCount(2);
  await expect(rows.nth(1).getByRole('cell').nth(3)).toHaveText('Por ativar');
  expect(creates).toEqual([201]);
  expect(issues).toEqual([401, 200]);

  // The code is a secret: nowhere on the page, in storage or in the address once closed (only booleans reported).
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(stored.includes(enrolmentCode), 'enrolment code kept in storage').toBe(false);
  expect(page.url().includes(enrolmentCode), 'enrolment code in the URL').toBe(false);
  expect((await page.content()).includes(enrolmentCode), 'enrolment code still on the page').toBe(false);
});
