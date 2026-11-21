import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The inventory area of the built BackOffice ("Stocks, Receitas & Compras"), its API stood in by page.route (the
// preview has no BFF): the Stock tab with its summary, the waste dialog, the Compras and Fichas técnicas tabs, axe on
// each. Screenshots at 1440 and 390 wide only when E2E_SCREENS is set (into SCREENSHOT_DIR when that is set too).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const COZINHA = 'c0000000-0000-4000-8000-000000000001';
const POLVO = 'd0000000-0000-4000-8000-000000000001';
const AZEITE = 'd0000000-0000-4000-8000-000000000002';
const SUPPLIER = 'e0000000-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_INVENTORY_VIEW', 'PERM_INVENTORY_MOVE', 'PERM_PURCHASE_MANAGE', 'PERM_GOODS_RECEIVE', 'PERM_RECIPE_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const page = <T>(content: T[]) => ({ content, page: 0, size: 50, totalElements: content.length });

const inventory: Record<string, unknown> = {
  warehouses: [{ id: COZINHA, storeId: STORE, code: 'COZ', name: 'Cozinha', active: true }],
  'stock-items': page([
    { id: POLVO, code: 'STK-POL-001', name: 'Polvo fresco', baseUnit: 'kg', kind: 'PURCHASED', active: true },
    { id: AZEITE, code: 'STK-AZT-004', name: 'Azeite virgem extra', baseUnit: 'L', kind: 'PURCHASED', active: true },
  ]),
  stock: page([
    { stockItemId: POLVO, warehouseId: COZINHA, code: 'STK-POL-001', name: 'Polvo fresco', baseUnit: 'kg', quantity: '3.500',
      minQuantity: '5.000', belowMinimum: true, averageCost: '9.80', value: '34.30' },
    { stockItemId: AZEITE, warehouseId: COZINHA, code: 'STK-AZT-004', name: 'Azeite virgem extra', baseUnit: 'L', quantity: '42',
      minQuantity: '10', belowMinimum: false, averageCost: '7.50', value: '315.00' },
  ]),
  'reports/valuation': { storeId: STORE, totalValue: '14820.50', rows: [] },
  'reports/below-minimum': { storeId: STORE, totalValue: '34.30', rows: [{ stockItemId: POLVO }] },
  suppliers: page([{ id: SUPPLIER, code: 'SUP-01', name: 'Lusitânia Pescados', taxId: '400123456', active: true }]),
  'purchase-orders': page([
    { id: 'f0000000-0000-4000-8000-000000000001', storeId: STORE, warehouseId: COZINHA, supplierId: SUPPLIER, number: 'OC-904',
      status: 'SUBMITTED', progress: 'PARTIAL', expectedAt: '2026-11-21',
      lines: [{ id: 'f1', stockItemId: POLVO, quantity: '100', ordered: '100', received: '80', outstanding: '20', progress: 'PARTIAL' }] },
  ]),
  receipts: page([
    { id: 'f2000000-0000-4000-8000-000000000001', supplierId: SUPPLIER, supplierDocumentRef: 'GR-882', status: 'DRAFT',
      receivedAt: '2026-11-20T07:15:00Z' },
  ]),
  recipes: page([
    { id: 'a1', name: 'Polvo à lagareiro', versions: [
      { id: 'a11', versionNo: 1, status: 'ACTIVE', yieldQuantity: '1', yieldUnit: 'un', ingredients: [
        { stockItemId: POLVO, quantity: '0.350', unit: 'kg', kind: 'INGREDIENT' },
      ] },
      { id: 'a12', versionNo: 2, status: 'DRAFT', yieldQuantity: '1', yieldUnit: 'un', ingredients: [] },
    ] },
  ]),
};

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: 1 } }),
  );
  await page.route('**/api/v1/backoffice/inventory/**', (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1/backoffice/inventory/', '');
    const body = inventory[path];
    return body === undefined ? route.fulfill({ status: 404, json: { status: 404, code: 'NOT_FOUND', title: 'Not found' } }) : route.fulfill({ json: body });
  });
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  if (!process.env.E2E_SCREENS) return;
  const dir = process.env.SCREENSHOT_DIR;
  const { width } = page.viewportSize()!;
  await page.screenshot({ path: dir ? `${dir}/${name}-${width}.png` : testInfo.outputPath(`${name}-${width}.png`), fullPage: true });
}

for (const viewport of [
  { width: 1440, height: 900 },
  { width: 390, height: 844 },
]) {
  test(`inventory at ${viewport.width}: stock, waste dialog, purchasing and recipes, axe clean`, async ({ page }, testInfo) => {
    await page.setViewportSize(viewport);
    await standIn(page);

    await page.goto(`/inventory?storeId=${STORE}`);
    const stock = page.getByRole('table', { name: 'Stock do armazém' });
    await expect(stock.getByRole('row')).toHaveCount(3);
    await expect(page.getByRole('group', { name: 'Abaixo do mínimo' })).toContainText('1 artigo');
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'inventory-stock');

    await page.getByRole('button', { name: 'Registar quebra' }).click();
    const dialog = page.getByRole('dialog', { name: 'Registar quebra' });
    await expect(dialog.getByRole('combobox', { name: 'Artigo' })).toBeVisible();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'inventory-waste-dialog');
    await dialog.getByRole('button', { name: 'Cancelar' }).click();
    await expect(dialog).toBeHidden();

    await page.getByRole('tab', { name: 'Compras' }).click();
    await expect(page).toHaveURL(/tab=compras/);
    await expect(page.getByRole('table', { name: 'Encomendas a fornecedores' }).getByText('Lusitânia Pescados')).toBeVisible();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'inventory-purchasing');

    await page.getByRole('button', { name: 'Nova encomenda' }).click();
    await expect(page.getByRole('dialog', { name: 'Nova encomenda' })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.getByRole('dialog', { name: 'Nova encomenda' }).getByRole('button', { name: 'Cancelar' }).click();

    await page.getByRole('tab', { name: 'Fichas técnicas' }).click();
    await expect(page.getByRole('heading', { name: 'Ficha técnica: Polvo à lagareiro' })).toBeVisible();
    await expect(page.getByRole('table', { name: 'Ingredientes da versão ativa' }).getByText('Polvo fresco')).toBeVisible();
    await expectNoAxeViolations(page);
    await screenshot(page, testInfo, 'inventory-recipes');
  });
}
