import { expect, type Page, test } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { expectNoAxeViolations } from '../axe';
import { enrolAndLogIn, expectLive, openTable, orderPanel, screens } from '../support/pos';
import { backOfficeCall, seeded, signInToBackOffice, stack } from '../support/stack';

// What disturbs the whole stack runs here, in the `stack-serial` project, after every other project has finished
// (playwright.config.ts): stopping the backend would fail the others' requests, and a BackOffice sign-in here must
// not race another one for a TOTP period. One terminal (C), one page, in order: the outage, then the revocation.
const COMPOSE = fileURLToPath(new URL('../../../deploy/e2e/compose.yaml', import.meta.url));
const compose = (...args: string[]) => execFileSync('docker', ['compose', '-f', COMPOSE, ...args], { stdio: 'ignore' });

test.describe.configure({ mode: 'serial' });
test.use({ viewport: { width: 1024, height: 768 } });

let page: Page;

test.beforeAll(async ({ browser }) => {
  page = await (await browser.newContext({ viewport: { width: 1024, height: 768 } })).newPage();
});

test.afterAll(async () => {
  // Never leave the stack without its backend, whatever failed.
  compose('up', '-d', '--wait', '--no-build', 'backend');
  await page.context().close();
});

test('with the backend stopped the POS says "Sem ligação", "Pedir" is disabled and nothing shows as sent', async () => {
  test.setTimeout(240_000);
  await enrolAndLogIn(page, 'C');
  await openTable(page, 'M2', 3);
  await page.getByRole('button', { name: 'Bebidas' }).click();
  await page.getByRole('button', { name: /^Água 50cl/ }).click();
  const panel = orderPanel(page);
  await expect(panel.getByRole('list', { name: 'Por enviar' }).getByRole('listitem')).toHaveCount(1);
  await expect(panel.getByRole('button', { name: 'Pedir' })).toBeEnabled();

  compose('stop', 'backend');

  await expect(page.getByRole('status', { name: 'Ligação' })).toHaveText(/Sem ligação/, { timeout: 30_000 });
  await expect(page.getByText('Sem ligação — nenhuma operação é confirmada').first()).toBeVisible();
  await expect(panel.getByRole('button', { name: 'Pedir' })).toBeDisabled();
  await expect(panel.getByText('Nada enviado ainda.')).toBeVisible();
  await expect(panel.getByRole('list', { name: /^Ronda/ })).toHaveCount(0);
  await expectNoAxeViolations(page);
  await screens(page, 'order-offline');

  compose('up', '-d', '--wait', '--no-build', 'backend');

  // Back: the order read again from the server still has its draft, and no round.
  await expectLive(page, 90_000);
  await expect(panel.getByRole('button', { name: 'Pedir' })).toBeEnabled();
  await expect(panel.getByRole('list', { name: 'Por enviar' }).getByRole('listitem')).toHaveCount(1);
  await expect(panel.getByText('Nada enviado ainda.')).toBeVisible();
});

test('a terminal revoked in the BackOffice shows the revoked state, and goes back to enrolment', async ({ browser }) => {
  test.setTimeout(180_000);
  const admin = await (await browser.newContext()).newPage();
  // Retry 1 makes the sign-in wait for a TOTP period no earlier sign-in of this run can have used (support/totp.ts).
  await signInToBackOffice(admin, seeded.adminEmail(), seeded.adminPassword(), seeded.adminTotpSecret(), 1);
  await backOfficeCall(admin, 'PUT', '/bff/tenant', { tenantId: seeded.tenantId() });
  // As the SPA writes: naming the selected company (X-Expected-Tenant, FE-F16).
  await backOfficeCall(admin, 'POST', `/api/v1/backoffice/terminals/${seeded.terminal('C').id}/revoke`, undefined, {
    'X-Expected-Tenant': seeded.tenantId(),
  });
  await admin.context().close();

  // Keycloak disables the device's client once the revocation commits; the terminal's next token is refused.
  const revoked = page.getByRole('heading', { name: 'Terminal revogado ou inválido' });
  await expect(async () => {
    await page.goto(`${stack.pos()}/tables`);
    await expect(revoked).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 60_000 });
  await expectNoAxeViolations(page);
  await screens(page, 'revoked');

  await page.getByRole('button', { name: 'Registar de novo' }).click();
  await page.getByRole('alertdialog').getByRole('button', { name: 'Apagar e registar de novo' }).click();

  await expect(page.getByRole('button', { name: 'Registar terminal' })).toBeVisible();
  await expect(page).toHaveURL(/\/enrol$/);
  await expectNoAxeViolations(page);
});
