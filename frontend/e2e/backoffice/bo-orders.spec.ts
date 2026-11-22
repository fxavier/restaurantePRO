import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The orders area of the built BackOffice, its API stood in by page.route (the preview has no BFF): the search with
// its filters, and the detail pane of one order, with axe on each. Screens at 1440 and 390 when E2E_SCREENS is set
// (into SCREENSHOT_DIR when that is set too).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const STORE = '11111111-0000-4000-8000-000000000001';
const ORDER = '50000000-0000-4000-8000-000000001043';
const BATCH = '60000000-0000-4000-8000-000000000001';
const EMPLOYEE = '30000000-0000-4000-8000-000000000001';
const session = { kind: 'ACCOUNT', name: 'Ana Admin', permissions: ['PERM_ORDER_VIEW'], tenantId: TENANT, tenantName: 'Restaurante A, Lda' };
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [{ id: STORE, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', currency: 'MZN' }];
const mzn = (amount: string) => ({ amount, currency: 'MZN' });

const row = (orderNumber: number, extra: Record<string, unknown> = {}) => ({
  id: `50000000-0000-4000-8000-${String(orderNumber).padStart(12, '0')}`,
  storeId: STORE,
  orderNumber,
  mode: 'DINE_IN',
  tableCode: 'M12',
  guests: 4,
  customerId: null,
  lifecycle: 'OPEN',
  businessDay: '2026-11-20',
  openedAt: '2026-11-20T18:05:00Z',
  closedAt: null,
  due: mzn('1250.50'),
  ...extra,
});
const rows = [
  row(1043),
  row(1042, { mode: 'TAKEAWAY', tableCode: null, guests: null, lifecycle: 'CLOSED', closedAt: '2026-11-20T17:25:00Z', due: mzn('0.00') }),
  row(1041, { mode: 'DELIVERY', tableCode: null, guests: null, lifecycle: 'CANCELLED', due: mzn('0.00') }),
];

const line = (id: string, description: string, extra: Record<string, unknown> = {}) => ({
  id,
  itemId: '70000000-0000-4000-8000-000000000001',
  itemCode: 'PRATO-1',
  description,
  variantId: null,
  variantName: null,
  kind: 'STANDARD',
  batchId: BATCH,
  status: 'CONFIRMED',
  quantity: '2',
  netQuantity: '2',
  unitPrice: mzn('450.00'),
  listUnitPrice: mzn('450.00'),
  gross: mzn('900.00'),
  discount: null,
  discountLabel: null,
  taxRatePercent: '16',
  notes: null,
  origin: null,
  preview: false,
  automatic: false,
  promotionCode: null,
  promotionName: null,
  components: [],
  adjustments: [],
  version: 0,
  ...extra,
});
const confirmed = [
  line('90000000-0000-4000-8000-000000000001', 'Frango à zambeziana'),
  line('90000000-0000-4000-8000-000000000002', 'Sumo de maracujá', {
    quantity: '1',
    unitPrice: mzn('0.00'),
    gross: mzn('120.00'),
    discount: mzn('120.00'),
    discountLabel: 'Oferta',
    adjustments: [
      { id: '80000000-0000-4000-8000-000000000001', seq: 1, kind: 'OFFER', quantity: '1', percent: null, previousUnitPrice: mzn('120.00'),
        unitPrice: mzn('0.00'), reason: 'Aniversário do cliente', rewardCode: null, operatorEmployeeId: EMPLOYEE, approverEmployeeId: null,
        at: '2026-11-20T18:40:00Z' },
    ],
  }),
];
const detail = {
  ...row(1043),
  tableId: 'a0000000-0000-4000-8000-000000000001',
  billingLock: 'NONE',
  openedByEmployeeId: EMPLOYEE,
  terminalId: 'b0000000-0000-4000-8000-000000000001',
  catalogVersion: 3,
  promotionVersion: null,
  draftGross: mzn('60.00'),
  due: mzn('900.00'),
  version: 4,
  batches: [{ id: BATCH, orderId: ORDER, batchNo: 1, status: 'DISPATCHED', confirmedAt: '2026-11-20T18:12:00Z', confirmedByEmployeeId: EMPLOYEE, orderVersion: 2, lines: confirmed }],
  lines: [
    ...confirmed,
    line('90000000-0000-4000-8000-000000000003', 'Pão com manteiga', { batchId: null, status: 'DRAFT', quantity: '1', unitPrice: mzn('60.00'), gross: mzn('60.00') }),
  ],
};
const ORDERS = `/orders?storeId=${STORE}`;

async function standIn(page: Page) {
  const searches: URLSearchParams[] = [];
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) => route.fulfill({ json: { content: stores, page: 0, size: 100, totalElements: 1 } }));
  await page.route('**/api/v1/backoffice/orders?**', (route) => {
    const query = new URL(route.request().url()).searchParams;
    searches.push(query);
    const content = query.get('status') ? rows.filter((each) => each.lifecycle === query.get('status')) : rows;
    return route.fulfill({ json: { content, page: 0, size: 50, totalElements: content.length } });
  });
  await page.route(`**/api/v1/backoffice/orders/${ORDER}`, (route) => route.fulfill({ json: detail }));
  return searches;
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

test('orders search: the list from the server and a filter by state, axe clean', async ({ page }, testInfo) => {
  const searches = await standIn(page);
  await page.goto(ORDERS);

  await expect(page.getByRole('heading', { level: 1, name: 'Pedidos' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Áreas' }).getByRole('link', { name: 'Pedidos' })).toHaveAttribute('aria-current', 'page');
  const table = page.getByRole('table', { name: 'Pedidos' });
  await expect(table.getByRole('row')).toHaveCount(4);
  await expect(table).toContainText('Take-away');
  await expect(table).toContainText('Cancelado');
  await expect(page.getByText('€')).toHaveCount(0);
  await expectNoAxeViolations(page);
  await page.setViewportSize({ width: 390, height: 900 });
  await expectNoAxeViolations(page);
  await page.setViewportSize({ width: 1280, height: 800 });
  await screens(page, testInfo, 'orders-search');

  await page.getByLabel('Estado').selectOption({ label: 'Fechado' });
  await expect(table.getByRole('row')).toHaveCount(2);
  expect(searches.at(-1)?.get('status')).toBe('CLOSED');
});

test('order detail: lines, rounds and the offer with its reason, axe clean', async ({ page }, testInfo) => {
  await standIn(page);
  await page.goto(ORDERS);

  await page.getByRole('button', { name: 'Ver pedido #1043' }).click();
  const detail = page.getByRole('region', { name: 'Pedido #1043' });
  await expect(detail.getByRole('table', { name: 'Linhas' }).getByRole('row')).toHaveCount(4);
  await expect(detail.getByRole('table', { name: 'Rondas' })).toContainText('Enviada');
  await expect(detail.getByRole('table', { name: 'Descontos e ofertas' })).toContainText('Aniversário do cliente');
  await expect(page).toHaveURL(new RegExp(`orderId=${ORDER}`));
  await expectNoAxeViolations(page);
  await page.setViewportSize({ width: 390, height: 900 });
  await expectNoAxeViolations(page);
  await page.setViewportSize({ width: 1280, height: 800 });
  await screens(page, testInfo, 'orders-detail');

  await detail.getByRole('button', { name: 'Fechar detalhe' }).click();
  await expect(detail).toHaveCount(0);
});
