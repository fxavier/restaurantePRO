import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The finance area of the built BackOffice, its API stood in by page.route (the preview has no BFF): the summary, the
// closings tab with its reject and reopen dialogs, and an approval that meets a step-up, with axe on each. Screens at
// 1440 and 390 when E2E_SCREENS is set (into SCREENSHOT_DIR when that is set too).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const REGISTER = 'eeee0000-0000-4000-8000-000000000001';
const CLOSING = 'cccc0000-0000-4000-8000-000000000001';
const DAY = 'dddd0000-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: [
    'PERM_CASH_VIEW',
    'PERM_CASH_CLOSING_APPROVE',
    'PERM_CASH_DAY_CLOSE',
    'PERM_CASH_DAY_REOPEN',
    'PERM_REPORT_VIEW',
    'PERM_FISCAL_DOCUMENT_VIEW',
    'PERM_FISCAL_MANAGE',
    'PERM_PAYMENT_METHOD_MANAGE',
    'PERM_AUDIT_VIEW',
  ],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const mzn = (amount: string) => ({ amount, currency: 'MZN' });
const sales = (key: string, gross: string, documents: number) => ({
  key,
  label: key,
  gross: mzn(gross),
  net: mzn(gross),
  tax: mzn('0.00'),
  quantity: '1',
  documents,
});
const closing = {
  id: CLOSING,
  storeId: STORE,
  cashDayId: DAY,
  registerId: REGISTER,
  dayRound: 1,
  attemptNo: 1,
  status: 'SUBMITTED',
  version: 1,
  lines: [
    { methodCode: 'CASH', expected: mzn('590.50'), counted: mzn('590.50'), difference: mzn('0.00') },
    { methodCode: 'CARD', expected: mzn('1240.80'), counted: mzn('1200.00'), difference: mzn('-40.80'), justification: 'Talão em falta' },
  ],
};
const reconciliation = [
  { cashDayId: DAY, businessDate: '2026-11-19', dayStatus: 'OPERATIONALLY_CLOSED', dayRound: 1, registerId: REGISTER, closingId: CLOSING,
    closingStatus: 'SUBMITTED', methodCode: 'CASH', expected: mzn('590.50'), counted: mzn('590.50'), adjustments: mzn('0.00'), difference: mzn('0.00') },
];
const FINANCE = `/finance?storeId=${STORE}`;

async function standIn(page: Page) {
  const approvals: (string | null)[] = [];
  const page1 = (content: unknown[]) => ({ content, page: 0, size: 20, totalElements: content.length });
  const report = (rows: unknown[], totals: unknown) => ({ storeId: STORE, final: true, pendingFacts: 0, rows, totals });
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) => route.fulfill({ json: { ...page1(stores), size: 100 } }));
  await page.route('**/api/v1/backoffice/reports/sales?**', (route) =>
    route.fulfill({ json: report([sales('2026-11-19', '1200.00', 30), sales('2026-11-20', '1640.90', 38)], sales('t', '2840.90', 68)) }),
  );
  await page.route('**/api/v1/backoffice/reports/receipts?**', (route) =>
    route.fulfill({
      json: report(
        [{ key: 'CASH', label: 'CASH', received: mzn('590.50'), onAccount: mzn('0.00') }],
        { key: 't', label: 'Total', received: mzn('1831.30'), onAccount: mzn('0.00') },
      ),
    }),
  );
  await page.route('**/api/v1/backoffice/cash/closings?**', (route) => route.fulfill({ json: page1([closing]) }));
  await page.route('**/api/v1/backoffice/cash/registers?**', (route) =>
    route.fulfill({ json: page1([{ id: REGISTER, storeId: STORE, code: 'CX1', name: 'Caixa principal', active: true, version: 0 }]) }),
  );
  await page.route('**/api/v1/backoffice/cash/reconciliation?**', (route) => route.fulfill({ json: reconciliation }));
  // Approving meets a stale second factor: the hop to the BFF's step-up keeps the command.
  await page.route('**/api/v1/backoffice/cash/closings/*/approve', (route) => {
    approvals.push(route.request().headers()['idempotency-key'] ?? null);
    return route.fulfill({
      status: 401,
      contentType: 'application/problem+json',
      headers: { 'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="2", max_age=300' },
      body: JSON.stringify({ status: 401, code: 'MFA_STEP_UP_REQUIRED', title: 'Step-up required', acrValues: '2', maxAge: 300 }),
    });
  });
  await page.route('**/bff/step-up?**', (route) =>
    route.fulfill({ contentType: 'text/html', body: '<!doctype html><html lang="pt"><title>Keycloak</title></html>' }),
  );
  return approvals;
}

async function screens(page: Page, testInfo: TestInfo, name: string) {
  if (!process.env.E2E_SCREENS) return;
  const dir = process.env.SCREENSHOT_DIR;
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ fullPage: true, path: dir ? `${dir}/${name}-${width}.png` : testInfo.outputPath(`${name}-${width}.png`) });
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

test('finance summary: KPIs and the per-day table from the server, axe clean', async ({ page }, testInfo) => {
  await standIn(page);
  await page.goto(FINANCE);

  await expect(page.getByRole('heading', { level: 1, name: 'Financeiro, Fechos & Auditoria' })).toBeVisible();
  await expect(page.getByRole('group', { name: 'Faturação bruta' })).toContainText('68 documentos');
  await expect(page.getByRole('table', { name: 'Vendas por dia' }).getByRole('row')).toHaveCount(3);
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'finance-summary');
});

test('closings: the lines, the reject and reopen dialogs, and an approval that steps up, axe clean', async ({ page }, testInfo) => {
  const approvals = await standIn(page);
  await page.goto(`${FINANCE}&tab=fechos`);

  await expect(page.getByRole('table', { name: 'Fecho Caixa principal, ronda 1' })).toContainText('Talão em falta');
  await expect(page.getByRole('table', { name: 'Dias de caixa' })).toContainText('19/11/2026');
  await expectNoAxeViolations(page);
  await screens(page, testInfo, 'finance-closings');

  await page.getByRole('button', { name: 'Rejeitar fecho Caixa principal' }).click();
  const reject = page.getByRole('dialog', { name: 'Rejeitar fecho' });
  await reject.getByRole('button', { name: 'Rejeitar' }).click();
  await expect(reject.getByText('Indique o motivo.')).toBeVisible();
  await expectNoAxeViolations(page);
  await reject.getByRole('button', { name: 'Cancelar' }).click();

  await page.getByRole('button', { name: 'Reabrir o dia 19/11/2026' }).click();
  await expect(page.getByRole('dialog', { name: 'Reabrir o dia 19/11/2026' }).getByLabel('PIN do aprovador')).toHaveAttribute('type', 'password');
  await expectNoAxeViolations(page);
  await page.getByRole('dialog').getByRole('button', { name: 'Cancelar' }).click();

  await page.getByRole('button', { name: 'Aprovar fecho Caixa principal' }).click();
  const stepUp = page.getByRole('alertdialog', { name: 'Confirme a sua identidade' });
  await expect(stepUp).toBeVisible();
  await expectNoAxeViolations(page);
  await stepUp.getByRole('button', { name: 'Confirmar identidade' }).click();
  await page.waitForURL('**/bff/step-up?**');
  const target = new URL(page.url());
  expect(target.searchParams.get('return')).toBe(`${FINANCE}&tab=fechos`);
  expect(approvals).toHaveLength(1);
  expect(approvals[0]).toMatch(/^[0-9a-f-]{36}$/);
});
