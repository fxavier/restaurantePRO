import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The terminals area of the built BackOffice, its API stood in by page.route (the preview has no BFF): the list, the
// step-up dialog and hop, the resume page and the one-time code dialog, with axe on each and screenshots at the DS
// BackOffice viewports (SCREENSHOT_DIR, when set). The real flow through the BFF and Keycloak is e2e/stack.

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_TERMINAL_MANAGE', 'PERM_STORE_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo' }];
const terminals = [
  { id: 'aaaa0000-0000-4000-8000-000000000001', storeId: STORE, code: 'CX1', name: 'Caixa 1', status: 'ACTIVE', kind: 'POS' },
  { id: 'aaaa0000-0000-4000-8000-000000000002', storeId: STORE, code: 'BAR', name: 'Bar', status: 'REGISTERED', kind: 'POS' },
  { id: 'aaaa0000-0000-4000-8000-000000000003', storeId: STORE, code: 'PB1', name: 'Impressoras', status: 'REVOKED', kind: 'PRINT_BRIDGE' },
];
const CODE = 'ABCD1234EFGH5678JKMN9PQR';
const TERMINALS = `/organization/terminals?storeId=${STORE}`;

async function standIn(page: Page) {
  const issued: (string | null)[] = [];
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: 1 } }),
  );
  await page.route('**/api/v1/backoffice/terminals?**', (route) =>
    route.fulfill({ json: { content: terminals, page: 0, size: 50, totalElements: terminals.length } }),
  );
  // The first issue meets a stale second factor; after the step-up the same request succeeds.
  await page.route('**/api/v1/backoffice/terminals/*/enrollment-code', (route) => {
    issued.push(route.request().headers()['idempotency-key'] ?? null);
    return issued.length === 1
      ? route.fulfill({
          status: 401,
          contentType: 'application/problem+json',
          headers: { 'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=300' },
          body: JSON.stringify({ status: 401, code: 'MFA_STEP_UP_REQUIRED', title: 'Step-up required', acrValues: '2', maxAge: 300 }),
        })
      : route.fulfill({
          json: { terminalId: terminals[1]!.id, code: CODE, expiresAt: '2026-11-19T08:00:00Z' },
          headers: { 'Cache-Control': 'no-store' },
        });
  });
  // Keycloak and the BFF's return are out of the preview: the hop lands on a stand-in page of this origin.
  await page.route('**/bff/step-up?**', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="pt"><title>Keycloak</title></html>' }),
  );
  return issued;
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  const dir = process.env.SCREENSHOT_DIR;
  const { width, height } = page.viewportSize()!;
  await page.screenshot({ path: dir ? `${dir}/${name}-${width}x${height}.png` : testInfo.outputPath(`${name}.png`) });
}

for (const viewport of [
  { width: 1280, height: 800 },
  { width: 1024, height: 768 },
]) {
  test(`terminals at ${viewport.width}×${viewport.height}: list, step-up, resume and the code shown once, axe clean`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    const issued = await standIn(page);

    await page.goto(TERMINALS);
    const table = page.getByRole('table', { name: 'Terminais da loja' });
    await expect(table.getByRole('row')).toHaveCount(4);
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'terminals-list');

    await page.getByRole('button', { name: 'Emitir código para Bar' }).click();
    const stepUp = page.getByRole('alertdialog', { name: 'Confirme a sua identidade' });
    await expect(stepUp).toBeVisible();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'step-up-dialog');

    await stepUp.getByRole('button', { name: 'Confirmar identidade' }).click();
    await expect(page).toHaveURL(/\/bff\/step-up\?acr_values=2&max_age=300&return=%2Fstep-up%2Fresume$/);

    await page.goto('/step-up/resume');
    await expect(page.getByText('Emitir o código de registo do terminal Bar.')).toBeVisible();
    await expectNoAxeViolations(page);
    await page.getByRole('button', { name: 'Continuar' }).click();

    const dialog = page.getByRole('dialog', { name: 'Código de registo de Bar' });
    await expect(dialog.getByText('ABCD 1234 EFGH 5678 JKMN 9PQR')).toBeVisible();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'enrolment-code-dialog');
    expect(issued).toEqual([null, null]);

    await dialog.getByRole('button', { name: 'Fechar' }).click();
    await expect(page).toHaveURL(TERMINALS);
    await expect(page.getByText(CODE.slice(0, 4))).toHaveCount(0);
    const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
    expect(stored).not.toContain(CODE);
    expect(page.url()).not.toContain(CODE);
  });
}
