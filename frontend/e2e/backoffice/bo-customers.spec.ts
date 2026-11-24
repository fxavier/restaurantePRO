import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The customers area of the built BackOffice ("Clientes, Crédito & Conta Corrente"), its API stood in by page.route (the
// preview has no BFF): the debts list, a customer's detail with its credit account and statement, the limit dialog,
// and the loyalty tab; axe on each. Screenshots at 1440, 1024 and 390 wide only when E2E_SCREENS is set (SCREENSHOT_DIR
// names where; else the test's output folder).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const CUSTOMER = 'c0000000-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: [
    'PERM_CUSTOMER_VIEW',
    'PERM_CUSTOMER_MANAGE',
    'PERM_CREDIT_LIMIT_MANAGE',
    'PERM_LOYALTY_VIEW',
    'PERM_LOYALTY_ENROLL',
    'PERM_LOYALTY_ADJUST',
  ],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const mzn = (amount: string) => ({ amount, currency: 'MZN' });
const customer = {
  id: CUSTOMER,
  name: 'Lusitânia Tech, Lda.',
  taxId: '501234567',
  email: 'financeiro@lusitania.co.mz',
  notes: 'Fatura mensal',
  active: true,
  version: 3,
  phones: [{ id: 'p0000000-0000-4000-8000-000000000001', digits: '258841234567', label: 'Escritório', primary: true }],
  addresses: [
    { id: 'a0000000-0000-4000-8000-000000000001', kind: 'BILLING', line1: 'Av. Julius Nyerere 102', city: 'Maputo', version: 0 },
  ],
};
const debt = (customerId: string, name: string, taxId: string, balance: string, over90 = '0.00') => ({
  customerId,
  name,
  taxId,
  balance: mzn(balance),
  days0To30: mzn(over90 === '0.00' ? balance : '0.00'),
  days31To60: mzn('0.00'),
  days61To90: mzn('0.00'),
  over90: mzn(over90),
});
const debts = [
  debt(CUSTOMER, 'Lusitânia Tech, Lda.', '501234567', '450.20'),
  debt('c0000000-0000-4000-8000-000000000002', 'Construtora Tejo & Filhos, SA', '509876543', '620.00', '620.00'),
  debt('c0000000-0000-4000-8000-000000000003', 'Sociedade Advogados R.L.', '504112233', '280.00'),
];
const credit = { limit: mzn('1000.00'), balance: mzn('450.20'), reserved: mzn('0.00'), available: mzn('549.80'), blocked: false };
const line = (n: number, kind: string, day: string, description: string, amount: string, balance: string) => ({
  entryId: `e0000000-0000-4000-8000-00000000000${n}`,
  kind,
  businessDate: day,
  occurredAt: `${day}T12:00:00Z`,
  description,
  amount: mzn(amount),
  balance: mzn(balance),
  settlements: [],
});
const statement = {
  customerId: CUSTOMER,
  openingBalance: mzn('0.00'),
  closingBalance: mzn('450.20'),
  lines: [
    line(1, 'DEBIT', '2026-11-02', 'Fatura FT 2026/1792', '490.00', '490.00'),
    line(2, 'CREDIT', '2026-11-10', 'Recebimento por transferência', '300.00', '190.00'),
    line(3, 'DEBIT', '2026-11-18', 'Fatura FT 2026/1840', '260.20', '450.20'),
  ],
};
const loyalty = { customerId: CUSTOMER, enrolled: true, points: 320, status: 'ACTIVE' };
const loyaltyStatement = {
  customerId: CUSTOMER,
  openingBalance: 0,
  closingBalance: 320,
  lines: [
    { id: 'l0000000-0000-4000-8000-000000000001', kind: 'EARN', businessDate: '2026-11-02', points: 300, balance: 300 },
    { id: 'l0000000-0000-4000-8000-000000000002', kind: 'ADJUSTMENT', businessDate: '2026-11-12', points: 20, balance: 320, reason: 'Compensação' },
  ],
};

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: 1 } }),
  );
  await page.route('**/api/v1/backoffice/customers/debts?**', (route) =>
    route.fulfill({ json: { content: debts, page: 0, size: 20, totalElements: debts.length } }),
  );
  await page.route(`**/api/v1/backoffice/customers/${CUSTOMER}`, (route) => route.fulfill({ json: customer }));
  await page.route(`**/api/v1/backoffice/customers/${CUSTOMER}/credit`, (route) => route.fulfill({ json: credit }));
  await page.route(`**/api/v1/backoffice/customers/${CUSTOMER}/statement`, (route) => route.fulfill({ json: statement }));
  await page.route(`**/api/v1/backoffice/loyalty/accounts/${CUSTOMER}`, (route) => route.fulfill({ json: loyalty }));
  await page.route(`**/api/v1/backoffice/loyalty/accounts/${CUSTOMER}/statement`, (route) =>
    route.fulfill({ json: loyaltyStatement }),
  );
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  if (!process.env.E2E_SCREENS) return;
  const dir = process.env.SCREENSHOT_DIR;
  const { width } = page.viewportSize()!;
  const path = dir ? `${dir}/${name}-${width}.png` : testInfo.outputPath(`${name}-${width}.png`);
  await page.screenshot({ path, fullPage: true, animations: 'disabled' });
}

test('customers: the debts, a customer with its credit and statement, the limit dialog and loyalty, axe clean', async ({
  page,
}) => {
  await standIn(page);

  await page.goto(`/customers?storeId=${STORE}`);
  const debtsTable = page.getByRole('table', { name: 'Clientes com saldo devedor' });
  await expect(debtsTable.getByRole('row')).toHaveCount(4);
  await expectNoAxeViolations(page);

  await debtsTable.getByRole('button', { name: 'Lusitânia Tech, Lda.' }).click();
  const detail = page.getByRole('region', { name: 'Lusitânia Tech, Lda.' });
  await expect(detail.getByText('NIF 501234567')).toBeVisible();
  await expectNoAxeViolations(page);

  await detail.getByRole('tab', { name: 'Crédito' }).click();
  const figures = detail.getByRole('list', { name: 'Conta de crédito' });
  await expect(figures).toContainText('549,80');
  await expect(detail.getByRole('table', { name: 'Extrato de conta corrente' }).getByRole('row')).toHaveCount(4);
  await expectNoAxeViolations(page);

  await detail.getByRole('button', { name: 'Alterar limite' }).click();
  const dialog = page.getByRole('dialog', { name: 'Alterar limite de crédito' });
  await expect(dialog.getByLabel('Novo limite')).toBeVisible();
  await expectNoAxeViolations(page);
  await dialog.getByRole('button', { name: 'Cancelar' }).click();
  await expect(dialog).toBeHidden();

  await detail.getByRole('tab', { name: 'Fidelização' }).click();
  await expect(detail.getByText('320 pontos')).toBeVisible();
  await expectNoAxeViolations(page);
});

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 1024, height: 768 },
  { width: 390, height: 844 },
]) {
  test(`customers screenshots at ${viewport.width}`, async ({ page }, testInfo) => {
    test.skip(!process.env.E2E_SCREENS, 'screenshots only when E2E_SCREENS is set');
    await page.setViewportSize(viewport);
    await standIn(page);

    await page.goto(`/customers?storeId=${STORE}&customerId=${CUSTOMER}`);
    const detail = page.getByRole('region', { name: 'Lusitânia Tech, Lda.' });
    await expect(page.getByRole('table', { name: 'Clientes com saldo devedor' }).getByRole('row')).toHaveCount(4);
    await screenshot(page, testInfo, 'bo-customers-data');
    await detail.getByRole('tab', { name: 'Crédito' }).click();
    await expect(detail.getByRole('table', { name: 'Extrato de conta corrente' })).toBeVisible();
    await screenshot(page, testInfo, 'bo-customers-credit');

    await detail.getByRole('button', { name: 'Alterar limite' }).click();
    await expect(page.getByRole('dialog', { name: 'Alterar limite de crédito' })).toBeVisible();
    await screenshot(page, testInfo, 'bo-customers-limit-dialog');
    await page.getByRole('dialog', { name: 'Alterar limite de crédito' }).getByRole('button', { name: 'Cancelar' }).click();

    await detail.getByRole('tab', { name: 'Fidelização' }).click();
    await expect(detail.getByText('320 pontos')).toBeVisible();
    await screenshot(page, testInfo, 'bo-customers-loyalty');
  });
}
