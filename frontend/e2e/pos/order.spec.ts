import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, standInDevice, STORE } from './stand-in';

// The order screen in the built POS, its API stood in by page.route: what jsdom cannot check, the three panes' real
// geometry at the smallest POS viewport (DS: 1024 × 768), the touch targets, and axe on the screen and its dialogs.
// With E2E_SCREENS set, the screens are also saved there at 1024 × 768 and 1366 × 768, for the PR.

const mzn = (amount: string) => ({ amount, currency: 'MZN' });
const item = (n: number, code: string, name: string, familyId: string, price: string, fields: object = {}) => ({
  id: id(n),
  code,
  name,
  familyId,
  price: mzn(price),
  variants: [],
  barcodes: [],
  kind: 'STANDARD',
  menuGroups: [],
  ...fields,
});

const DRINKS = id(601);
const DISHES = id(603);
const MENUS = id(604);
const SMALL = id(721);

const CATALOG = {
  releaseId: id(600),
  storeId: STORE,
  version: 7,
  catalog: {
    schemaVersion: 2,
    version: 7,
    currency: 'MZN',
    families: [
      { id: DRINKS, code: 'BEBIDAS', name: 'Bebidas' },
      { id: id(602), code: 'SOBREMESAS', name: 'Sobremesas' },
      { id: DISHES, code: 'PRATOS', name: 'Pratos' },
      { id: MENUS, code: 'MENUS', name: 'Menus' },
    ],
    items: [
      item(701, 'BICA', 'Bica', DRINKS, '50.00'),
      item(702, 'AGUA', 'Água', DRINKS, '60.00', {
        variants: [
          { id: SMALL, code: 'AGUA-P', name: 'Pequena' },
          { id: id(722), code: 'AGUA-G', name: 'Grande', price: mzn('90.00') },
        ],
      }),
      item(703, 'SUMO', 'Sumo de laranja natural', DRINKS, '120.00'),
      item(708, 'CERVEJA', 'Cerveja 2M', DRINKS, '90.00'),
      item(709, 'LAURENTINA', 'Laurentina Preta', DRINKS, '100.00'),
      item(710, 'REFRI', 'Refrigerante lata', DRINKS, '70.00'),
      item(711, 'CHA', 'Chá', DRINKS, '50.00'),
      item(712, 'VINHO', 'Vinho da casa (copo)', DRINKS, '150.00'),
      item(704, 'BITOQUE', 'Bitoque', DISHES, '450.00'),
      item(705, 'FRANGO', 'Frango grelhado', DISHES, '400.00'),
      item(706, 'MENU', 'Menu do dia', MENUS, '600.00', {
        kind: 'MENU',
        menuGroups: [
          {
            code: 'PRATO',
            name: 'Prato',
            minChoices: 1,
            maxChoices: 1,
            options: [
              { itemId: id(704), quantity: 1 },
              { itemId: id(705), quantity: 1 },
            ],
          },
          {
            code: 'BEBIDA',
            name: 'Bebidas',
            minChoices: 1,
            maxChoices: 2,
            options: [
              { itemId: id(702), variantId: SMALL, quantity: 1 },
              { itemId: id(701), quantity: 1 },
              { itemId: id(703), quantity: 1 },
            ],
          },
        ],
      }),
    ],
  },
};

const ORDER_ID = id(500);
const BATCH = id(550);
const draft = (n: number, fields: object) => ({
  id: id(n),
  status: 'DRAFT',
  quantity: 1,
  version: 0,
  preview: true,
  components: [],
  ...fields,
});
const sent = (n: number, description: string, quantity: number, gross: number) => ({
  id: id(n),
  status: 'CONFIRMED',
  description,
  quantity,
  netQuantity: quantity,
  version: 1,
  batchId: BATCH,
  gross: { amount: gross, currency: 'MZN' },
  preview: false,
  components: [],
});
const LINES = [
  draft(801, { itemId: id(702), variantId: SMALL, quantity: 2, notes: 'Sem gelo', version: 1, gross: { amount: 120, currency: 'MZN' } }),
  draft(802, {
    itemId: id(706),
    gross: { amount: 600, currency: 'MZN' },
    components: [
      { groupCode: 'PRATO', itemId: id(705) },
      { groupCode: 'BEBIDA', itemId: id(701) },
    ],
  }),
  draft(803, { itemId: id(701), gross: { amount: 50, currency: 'MZN' } }),
  sent(804, 'Bitoque', 3, 1350),
  sent(805, 'Cerveja 2M', 2, 180),
];
const ORDER = {
  id: ORDER_ID,
  storeId: STORE,
  orderNumber: 12,
  mode: 'DINE_IN',
  tableId: id(2),
  tableCode: 'M2',
  guests: 3,
  lifecycle: 'OPEN',
  billingLock: 'NONE',
  version: 4,
  catalogVersion: 7,
  lines: LINES,
  batches: [{ id: BATCH, orderId: ORDER_ID, batchNo: 1, status: 'DISPATCHED', confirmedAt: '2026-11-18T10:10:00Z', lines: [] }],
  due: { amount: 1530, currency: 'MZN' },
};
const table = (n: number, code: string, fields: object = {}) => ({
  id: id(n),
  code,
  seats: 4,
  status: 'FREE',
  locked: false,
  subtotalPrinted: false,
  reserved: false,
  ...fields,
});
const MAP = {
  storeId: STORE,
  areas: [
    {
      id: id(900),
      code: 'SALA',
      name: 'Sala',
      tables: [table(1, 'M1'), table(2, 'M2', { status: 'OCCUPIED', guests: 3, openedAt: '2026-11-18T10:05:00Z' })],
    },
  ],
};

async function standIn(page: Page) {
  await standInDevice(page);
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: MAP }));
  // The stream stays pending: this screen does not need a change.
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/catalog', (route) => route.fulfill({ json: CATALOG }));
  await page.route((url) => url.pathname === '/api/v1/pos/orders', (route) =>
    route.fulfill({ json: { content: [{ id: ORDER_ID, tableId: id(2), lifecycle: 'OPEN' }], page: 0, size: 200, totalElements: 1 } }),
  );
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}`, (route) => route.fulfill({ json: ORDER }));
}

async function atOrder(page: Page) {
  await standIn(page);
  await logIn(page);
  await page.getByRole('button', { name: /^Mesa M2 / }).click();
  await expect(page.getByRole('heading', { name: 'Mesa M2' })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Bica/ })).toBeVisible();
}

const box = async (locator: ReturnType<Page['getByRole']>) => (await locator.boundingBox())!;

test('at 1024 × 768 the three panes keep their widths, nothing scrolls sideways, and targets are touch-sized', async ({ page }) => {
  await atOrder(page);

  const rail = await box(page.getByRole('navigation', { name: 'Famílias' }));
  const panel = await box(page.getByRole('complementary', { name: 'Pedido' }));
  const products = await box(page.getByRole('region', { name: 'Produtos' }));
  expect(rail.width).toBe(160);
  expect(panel.width).toBe(360);
  expect(rail.x + rail.width).toBeLessThanOrEqual(products.x);
  expect(products.x + products.width).toBeLessThanOrEqual(panel.x);
  expect(panel.x + panel.width).toBeLessThanOrEqual(1024);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);

  // The total and the primary action stay on screen, under the lines.
  const send = await box(page.getByRole('button', { name: 'Pedir' }));
  expect(send.height).toBeGreaterThanOrEqual(56);
  expect(send.y + send.height).toBeLessThanOrEqual(768);
  await expect(page.getByRole('group', { name: 'Total' })).toBeInViewport();

  for (const tile of await page.getByRole('list', { name: 'Artigos' }).getByRole('button').all()) {
    const { width, height } = (await tile.boundingBox())!;
    expect(width).toBeGreaterThanOrEqual(112);
    expect(height).toBeGreaterThanOrEqual(80);
  }
  const targets = [
    ...(await page.getByRole('navigation', { name: 'Famílias' }).getByRole('button').all()),
    ...(await page.getByRole('list', { name: 'Por enviar' }).getByRole('button').all()),
    page.getByRole('link', { name: 'Voltar às mesas' }),
  ];
  for (const target of targets) {
    const { width, height } = (await target.boundingBox())!;
    expect(Math.min(width, height)).toBeGreaterThanOrEqual(48);
  }
});

test('the order screen is accessible, and so is its choice sheet', async ({ page }) => {
  await atOrder(page);
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Menus' }).click();
  await page.getByRole('button', { name: /^Menu do dia/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Menu do dia' });
  await expect(sheet).toBeVisible();
  const sheetBox = (await sheet.boundingBox())!;
  expect(sheetBox.y).toBeGreaterThanOrEqual(0);
  expect(sheetBox.y + sheetBox.height).toBeLessThanOrEqual(768);
  await expectNoAxeViolations(page);
});

test('the guests pad is accessible', async ({ page }) => {
  await standIn(page);
  await logIn(page);
  await page.getByRole('button', { name: /^Mesa M1 / }).click();
  await expect(page.getByRole('dialog', { name: 'Abrir mesa M1' })).toBeVisible();

  await expectNoAxeViolations(page);
});

test('screens for the PR, at 1024 × 768 and 1366 × 768', async ({ page }) => {
  const dir = process.env.E2E_SCREENS;
  test.skip(!dir, 'E2E_SCREENS not set');
  await atOrder(page);
  for (const [width, height] of [
    [1024, 768],
    [1366, 768],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.getByRole('button', { name: 'Bebidas' }).click();
    await page.screenshot({ path: `${dir}/order-${width}x${height}.png`, animations: 'disabled' });
    await page.getByRole('list', { name: /^Ronda 1/ }).scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${dir}/order-sent-${width}x${height}.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'Menus' }).click();
    await page.getByRole('button', { name: /^Menu do dia/ }).click();
    const sheet = page.getByRole('dialog', { name: 'Menu do dia' });
    await sheet.getByRole('checkbox', { name: 'Frango grelhado' }).check();
    await sheet.getByRole('checkbox', { name: 'Bica' }).check();
    await expect(sheet.getByRole('button', { name: 'Adicionar' })).toBeEnabled();
    await page.screenshot({ path: `${dir}/choice-sheet-${width}x${height}.png`, animations: 'disabled' });
    await sheet.getByRole('button', { name: 'Cancelar' }).click();
    await page.getByRole('link', { name: 'Voltar às mesas' }).click();
    await page.getByRole('button', { name: /^Mesa M1 / }).click();
    await page.getByRole('dialog', { name: 'Abrir mesa M1' }).getByRole('button', { name: '2', exact: true }).click();
    await page.screenshot({ path: `${dir}/guests-${width}x${height}.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await page.getByRole('button', { name: /^Mesa M2 / }).click();
    await expect(page.getByRole('heading', { name: 'Mesa M2' })).toBeVisible();
  }
});
