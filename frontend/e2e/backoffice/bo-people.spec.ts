import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The people screen of the built BackOffice, its API stood in by page.route (the preview has no BFF): the employees
// tab, the new-employee dialog, the PIN reset's step-up hop back to this tab, the roles tab and a role's permission
// matrix, with axe on each.
// With E2E_SCREENS set, screenshots at 1440 × 900, 1024 × 768 and 390 wide go to SCREENSHOT_DIR (or the test's output folder).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_EMPLOYEE_MANAGE', 'PERM_ROLE_MANAGE', 'PERM_USER_MANAGE', 'PERM_SUPPORT_ACCESS_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const roles = [
  { id: 'c1000000-0000-4000-8000-000000000001', name: 'Gerente', permissions: ['ORDER_VOID_APPROVE', 'CASH_VIEW'], version: 3 },
  { id: 'c1000000-0000-4000-8000-000000000002', name: 'Empregado de sala', permissions: ['ORDER_EDIT'], version: 0 },
];
const employees = [
  { id: 'e1000000-0000-4000-8000-000000000001', code: 'EMP-01', name: 'Maria Sitoe', active: true, locked: false, version: 0 },
  { id: 'e1000000-0000-4000-8000-000000000002', code: 'EMP-04', name: 'João Macuácua', active: true, locked: true, version: 0 },
  { id: 'e1000000-0000-4000-8000-000000000003', code: 'EMP-11', name: 'Rui Bila', active: false, locked: false, version: 0 },
];
const PIN = '908172';
/** `GET /backoffice/permissions` (`PermissionView`): a few areas, one permission sensitive. */
const catalogue = [
  { name: 'ORDER_VIEW', sensitive: false, area: 'ORDER' },
  { name: 'ORDER_EDIT', sensitive: false, area: 'ORDER' },
  { name: 'ORDER_VOID_APPROVE', sensitive: true, area: 'ORDER' },
  { name: 'CASH_VIEW', sensitive: false, area: 'CASH' },
  { name: 'CASH_DAY_REOPEN', sensitive: true, area: 'CASH' },
  { name: 'PRODUCTION_VIEW', sensitive: false, area: 'PRODUCTION' },
];

const page0 = <T>(content: T[]) => ({ json: { content, page: 0, size: 20, totalElements: content.length } });

async function standIn(page: Page) {
  const pinBodies: string[] = [];
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: 1 } }),
  );
  await page.route('**/api/v1/backoffice/employees?**', (route) => route.fulfill(page0(employees)));
  await page.route('**/api/v1/backoffice/roles?**', (route) => route.fulfill(page0(roles)));
  await page.route('**/api/v1/backoffice/users?**', (route) => route.fulfill(page0([])));
  await page.route('**/api/v1/backoffice/permissions', (route) => route.fulfill({ json: catalogue }));
  // The PIN reset meets a stale second factor.
  await page.route('**/api/v1/backoffice/employees/*/reset-pin', (route) => {
    pinBodies.push(route.request().postData() ?? '');
    return route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      headers: { 'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=300' },
      body: JSON.stringify({ status: 401, code: 'MFA_STEP_UP_REQUIRED', title: 'Step-up required', acrValues: '2', maxAge: 300 }),
    });
  });
  // Keycloak and the BFF's return are out of the preview: the hop lands on a stand-in page of this origin.
  await page.route('**/bff/step-up?**', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="pt"><title>Keycloak</title></html>' }),
  );
  return pinBodies;
}

async function screens(page: Page, testInfo: TestInfo, name: string) {
  if (!process.env.E2E_SCREENS) return;
  const dir = process.env.SCREENSHOT_DIR;
  const before = page.viewportSize()!;
  const heights: Record<number, number> = { 1440: 900, 1024: 768, 390: 844 };
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: heights[width]! });
    await page.screenshot({ path: dir ? `${dir}/${name}-${width}.png` : testInfo.outputPath(`${name}-${width}.png`), fullPage: true, animations: 'disabled' });
  }
  await page.setViewportSize(before);
}

test('people: employees, the new-employee dialog and the roles tab pass axe', async ({ page }, testInfo) => {
  await standIn(page);

  await page.goto(`/people?storeId=${STORE}`);
  const table = page.getByRole('table', { name: 'Empregados' });
  await expect(table.getByRole('row')).toHaveCount(4);
  await expect(page.getByRole('tab', { name: 'Empregados', selected: true })).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'people-employees');

  await page.getByRole('button', { name: 'Novo empregado' }).click();
  const dialog = page.getByRole('dialog', { name: 'Novo empregado' });
  await expect(dialog.getByLabel('Função')).toContainText('Gerente');
  await expect(dialog.getByLabel('Loja')).toHaveValue(STORE);
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'people-new-employee');
  await dialog.getByRole('button', { name: 'Cancelar' }).click();

  await page.getByRole('tab', { name: 'Funções' }).click();
  await expect(page).toHaveURL(`/people?storeId=${STORE}&tab=funcoes`);
  await expect(page.getByRole('table', { name: 'Funções' }).getByRole('row')).toHaveCount(3);
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'people-roles');
});

test("people: a role's permission matrix, grouped by area with the sensitive ones marked, passes axe and saves", async ({
  page,
}, testInfo) => {
  await standIn(page);
  const puts: unknown[] = [];
  await page.route('**/api/v1/backoffice/roles/*', (route) => {
    puts.push(route.request().postDataJSON());
    return route.fulfill({ json: { ...roles[0], permissions: ['ORDER_VOID_APPROVE', 'CASH_VIEW', 'PRODUCTION_VIEW'], version: 4 } });
  });

  await page.goto('/people?tab=funcoes');
  await page.getByRole('button', { name: 'Editar Gerente' }).click();
  const dialog = page.getByRole('dialog', { name: 'Editar a função Gerente' });
  const orders = dialog.getByRole('group', { name: 'Pedidos' });
  await expect(orders.getByRole('switch', { name: 'Aprovar anulações' })).toBeChecked();
  await expect(orders.getByRole('switch', { name: 'Aprovar anulações' })).toHaveAccessibleDescription(
    'ORDER_VOID_APPROVE Sensível Usá-la exige confirmar a identidade com o segundo fator.',
  );
  await expect(orders.getByText('1 / 3')).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'people-role-matrix');

  await dialog.getByRole('group', { name: 'Produção' }).getByRole('switch', { name: 'Ver a produção' }).click();
  await expect(dialog.getByRole('switch', { name: 'Ver a produção' })).toBeChecked();
  await dialog.getByRole('button', { name: 'Guardar' }).click();
  await expect(dialog).toBeHidden();
  expect(puts).toEqual([{ name: 'Gerente', permissions: ['ORDER_VOID_APPROVE', 'CASH_VIEW', 'PRODUCTION_VIEW'], version: 3 }]);
});

test('people: a PIN reset that needs a fresh second factor hops to the step-up and back to this tab, keeping no PIN', async ({
  page,
}) => {
  const pinBodies = await standIn(page);

  await page.goto('/people?tab=empregados');
  await page.getByRole('button', { name: 'Repor PIN de Maria Sitoe' }).click();
  const dialog = page.getByRole('dialog', { name: 'Repor o PIN de Maria Sitoe' });
  await dialog.getByLabel('Novo PIN').fill(PIN);
  await dialog.getByRole('button', { name: 'Repor PIN' }).click();

  const stepUp = page.getByRole('alertdialog', { name: 'Confirme a sua identidade' });
  await expect(stepUp).toBeVisible();
  await expectNoAxeViolations(page);
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage }) + JSON.stringify({ ...sessionStorage }));
  expect(stored).not.toContain(PIN);

  await stepUp.getByRole('button', { name: 'Confirmar identidade' }).click();
  await expect(page).toHaveURL(/\/bff\/step-up\?acr_values=2&max_age=300&return=/);
  // Back to this tab, with the store the top bar chose for the only store.
  const back = new URL(page.url()).searchParams.get('return')!;
  expect(back).toBe(`/people?tab=empregados&storeId=${STORE}`);
  expect(pinBodies).toEqual([JSON.stringify({ pin: PIN })]);
});
