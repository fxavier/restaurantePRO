import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The delivery area of the built BackOffice, its API stood in by page.route (the preview has no BFF): the zones with
// the "Nova zona" dialog, and the deliveries report, with axe on each. With E2E_SCREENS set, screenshots at 1440 and
// 390 wide go to SCREENSHOT_DIR (or the test output) for the comparison with the Stitch screens.

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_DELIVERY_ZONE_MANAGE', 'PERM_ORDER_VIEW', 'PERM_CATALOG_MANAGE', 'PERM_EMPLOYEE_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const fees = [
  { id: 'f0000000-0000-4000-8000-000000000001', code: 'TX-PERTO', name: 'Taxa de entrega perto', kind: 'FEE' },
  { id: 'f0000000-0000-4000-8000-000000000002', code: 'TX-LONGE', name: 'Taxa de entrega longe', kind: 'FEE' },
];
const zones = [
  { id: '20000000-0000-4000-8000-000000000001', storeId: STORE, code: 'Z1', name: 'Centro', feeItemId: fees[0]!.id, active: true, version: 0 },
  { id: '20000000-0000-4000-8000-000000000002', storeId: STORE, code: 'Z2', name: 'Polana', feeItemId: fees[1]!.id, active: true, version: 0 },
  { id: '20000000-0000-4000-8000-000000000003', storeId: STORE, code: 'Z3', name: 'Costa do Sol', feeItemId: fees[1]!.id, active: false, version: 2 },
];
const couriers = [
  { id: '30000000-0000-4000-8000-000000000001', code: 'E01', name: 'João Pereira', active: true },
  { id: '30000000-0000-4000-8000-000000000002', code: 'E02', name: 'Tiago Ramos', active: true },
];
const deliveries = [
  { orderNumber: 1082, status: 'DELIVERED', courierEmployeeId: couriers[0]!.id, dispatchedAt: '2026-11-20T18:17:00Z', deliveredAt: '2026-11-20T18:29:00Z' },
  { orderNumber: 1083, status: 'OUT_FOR_DELIVERY', courierEmployeeId: couriers[1]!.id, dispatchedAt: '2026-11-20T18:40:00Z' },
  { orderNumber: 1084, status: 'RETURNED', courierEmployeeId: couriers[0]!.id, dispatchedAt: '2026-11-20T17:55:00Z' },
  { orderNumber: 1085, status: 'PENDING' },
  { orderNumber: 1086, status: 'CANCELLED' },
].map((each, index) => ({
  orderId: `40000000-0000-4000-8000-00000000000${index}`,
  storeId: STORE,
  zoneId: zones[index % 2]!.id,
  zoneCode: zones[index % 2]!.code,
  promisedAt: '2026-11-20T18:30:00Z',
  history: [],
  version: 0,
  ...each,
}));

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) =>
    route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: stores.length } }),
  );
  await page.route('**/api/v1/backoffice/delivery-zones?**', (route) => route.fulfill({ json: zones }));
  await page.route('**/api/v1/backoffice/catalog/items?**', (route) =>
    route.fulfill({ json: { content: fees, page: 0, size: 200, totalElements: fees.length } }),
  );
  await page.route('**/api/v1/backoffice/catalog/items/*/variants', (route) => route.fulfill({ json: [] }));
  await page.route('**/api/v1/backoffice/employees?**', (route) =>
    route.fulfill({ json: { content: couriers, page: 0, size: 200, totalElements: couriers.length } }),
  );
  await page.route('**/api/v1/backoffice/deliveries?**', (route) =>
    route.fulfill({ json: { content: deliveries, page: 0, size: 50, totalElements: deliveries.length } }),
  );
}

/** Only with E2E_SCREENS: the screen at Stitch's desktop width and at a phone's, full page. */
async function screens(page: Page, name: string) {
  if (!process.env.E2E_SCREENS) return;
  const dir = process.env.SCREENSHOT_DIR ?? test.info().outputDir;
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.screenshot({ path: `${dir}/${name}-${width}.png`, fullPage: true });
  }
  await page.setViewportSize({ width: 1280, height: 800 });
}

test('delivery zones: the list and the "Nova zona" dialog, axe clean', async ({ page }) => {
  await standIn(page);

  await page.goto(`/delivery/zones?storeId=${STORE}`);
  const list = page.getByRole('list', { name: 'Zonas da loja' });
  await expect(list.getByRole('listitem')).toHaveCount(3);
  await expect(list.getByText('Taxa de entrega perto')).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, 'delivery-zones');

  await page.getByRole('button', { name: 'Nova zona' }).click();
  const dialog = page.getByRole('dialog', { name: 'Nova zona de entrega' });
  await expect(dialog.getByRole('option', { name: 'Taxa de entrega longe (TX-LONGE)' })).toBeAttached();
  await expectNoAxeViolations(page);
  await screens(page, 'delivery-zones-new');
});

test('delivery report: the period, the courier filter and the deliveries, axe clean', async ({ page }) => {
  await standIn(page);

  await page.goto(`/delivery/report?storeId=${STORE}`);
  const table = page.getByRole('table', { name: 'Entregas' });
  await expect(table.getByRole('row')).toHaveCount(deliveries.length + 1);
  await expect(page.getByRole('group', { name: 'Total de entregas' })).toContainText(String(deliveries.length));
  await expect(page.getByLabel('Estafeta')).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, 'delivery-report');
});
