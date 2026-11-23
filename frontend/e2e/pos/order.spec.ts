import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, openFromMap, standInDevice, STORE } from './stand-in';

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
      item(701, 'BICA', 'Bica', DRINKS, '50.00', { productionDestination: 'COPA' }),
      item(702, 'AGUA', 'Água', DRINKS, '60.00', {
        variants: [
          { id: SMALL, code: 'AGUA-P', name: 'Pequena' },
          { id: id(722), code: 'AGUA-G', name: 'Grande', price: mzn('90.00') },
        ],
      }),
      item(703, 'SUMO', 'Sumo de laranja natural', DRINKS, '120.00', { productionDestination: 'GRILL' }),
      item(708, 'CERVEJA', 'Cerveja 2M', DRINKS, '90.00', { productionDestination: 'BAR' }),
      item(709, 'LAURENTINA', 'Laurentina Preta', DRINKS, '100.00'),
      item(710, 'REFRI', 'Refrigerante lata', DRINKS, '70.00'),
      item(711, 'CHA', 'Chá', DRINKS, '50.00'),
      item(712, 'VINHO', 'Vinho da casa (copo)', DRINKS, '150.00'),
      item(704, 'BITOQUE', 'Bitoque', DISHES, '450.00', { productionDestination: 'KITCHEN' }),
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

/** An instant `minutes` before now: the map's times are recent, as on a working terminal, not days old. */
const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

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
  batches: [{ id: BATCH, orderId: ORDER_ID, batchNo: 1, status: 'DISPATCHED', confirmedAt: ago(20), lines: [] }],
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
      tables: [
        table(1, 'M1'),
        table(2, 'M2', { status: 'OCCUPIED', guests: 3, openedAt: ago(32), openOrderId: ORDER_ID }),
        // Too long without a round (the server's threshold is 30 min): red, "58 min sem ronda".
        table(3, 'M3', { status: 'OCCUPIED', guests: 2, openedAt: ago(75), openOrderId: id(503) }),
        table(4, 'M4', { status: 'OCCUPIED', guests: 4, openedAt: ago(90), openOrderId: id(504), subtotalPrinted: true }),
        table(5, 'M5', { reserved: true, nextReservationAt: new Date(Date.now() + 90 * 60_000).toISOString() }),
        table(6, 'M6'),
      ],
    },
  ],
};

/** The orders' side of the map: each seated order's total and last round, and the overdue threshold. */
const ACTIVITY = {
  overdueAfterMinutes: 30,
  tables: [
    { tableId: id(2), orderId: ORDER_ID, total: mzn('1530.00'), lastRoundAt: ago(20) },
    { tableId: id(3), orderId: id(503), total: mzn('480.00'), lastRoundAt: ago(58) },
    { tableId: id(4), orderId: id(504), total: mzn('2010.00'), lastRoundAt: ago(45) },
  ],
};

/** M2's first round is ready in the kitchen. */
const ROUNDS = [{ batchId: BATCH, status: 'READY' }];

/** Twelve rounds' worth of sent lines and no draft: the panel's body scrolls at 1024 × 768 with nothing to tab to. */
const ALL_SENT = {
  ...ORDER,
  lines: Array.from({ length: 12 }, (_, index) => sent(820 + index, `Prato ${index + 1}`, 1, 100)),
  due: { amount: 1200, currency: 'MZN' },
};

async function standIn(page: Page, order: object = ORDER) {
  await standInDevice(page);
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: MAP }));
  // The stream stays pending: this screen does not need a change.
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/catalog', (route) => route.fulfill({ json: CATALOG }));
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}`, (route) => route.fulfill({ json: order }));
  await page.route('**/api/v1/pos/orders/table-activity', (route) => route.fulfill({ json: ACTIVITY }));
  await page.route('**/api/v1/pos/production-tickets/rounds?*', (route) => route.fulfill({ json: ROUNDS }));
}

async function atOrder(page: Page, order: object = ORDER) {
  await standIn(page, order);
  await logIn(page);
  await openFromMap(page, 'M2');
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

// axe's scrollable-region-focusable: a scrolling body with no control inside must itself take the focus, or a
// keyboard user cannot scroll it (main's CI failed on the order panel once every line was sent).
test('an order with only sent lines scrolls, and its lines are reachable by keyboard', async ({ page }) => {
  await atOrder(page, ALL_SENT);
  await expect(page.getByText('Sem linhas por enviar.')).toBeVisible();

  await expectNoAxeViolations(page);

  const lines = page.getByRole('region', { name: 'Linhas do pedido' });
  expect(await lines.evaluate((element) => element.scrollHeight > element.clientHeight)).toBe(true);
  await lines.focus();
  await expect(lines).toBeFocused();
});

test('the guests pad is accessible', async ({ page }) => {
  await standIn(page);
  await logIn(page);
  await openFromMap(page, 'M1');
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
    await openFromMap(page, 'M1');
    await page.getByRole('dialog', { name: 'Abrir mesa M1' }).getByRole('button', { name: '2', exact: true }).click();
    await page.screenshot({ path: `${dir}/guests-${width}x${height}.png`, animations: 'disabled' });
    await page.getByRole('button', { name: 'Cancelar' }).click();
    await openFromMap(page, 'M2');
    await expect(page.getByRole('heading', { name: 'Mesa M2' })).toBeVisible();
  }
});

// The table map with M2 selected (light reference, 2026-11-23): its order in the right card, at the smallest POS viewport.
async function atM2OnMap(page: Page) {
  await standIn(page);
  await logIn(page);
  await page.getByRole('button', { name: /^Mesa M2 / }).click();
  await expect(page.getByRole('complementary', { name: 'Detalhe da mesa' }).getByRole('list', { name: 'Por enviar' })).toContainText(
    'Água Pequena',
  );
}

test('the selected table\'s order: on screen at 1024 × 768, touch-sized, and accessible', async ({ page }) => {
  await atM2OnMap(page);
  const panel = page.getByRole('complementary', { name: 'Detalhe da mesa' });

  const send = await box(panel.getByRole('button', { name: 'Enviar ronda 2' }));
  expect(send.height).toBeGreaterThanOrEqual(56);
  expect(send.y + send.height).toBeLessThanOrEqual(768);
  await expect(panel.getByRole('group', { name: 'Total' })).toBeInViewport();
  for (const target of [
    ...(await panel.getByRole('list', { name: 'Por enviar' }).getByRole('button').all()),
    panel.getByRole('link', { name: 'Subtotal' }),
    panel.getByRole('link', { name: 'Dividir' }),
  ]) {
    const { width, height } = (await target.boundingBox())!;
    expect(Math.min(width, height)).toBeGreaterThanOrEqual(48);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);

  // Compact enough that the three drafts show under the sent round without scrolling the panel's body.
  const body = await box(panel.getByRole('region', { name: 'Linhas do pedido' }));
  const drafts = await panel.getByRole('list', { name: 'Por enviar' }).getByRole('listitem').all();
  expect(drafts).toHaveLength(3);
  for (const line of drafts) {
    const { y, height } = (await line.boundingBox())!;
    expect(y).toBeGreaterThanOrEqual(body.y);
    expect(y + height).toBeLessThanOrEqual(body.y + body.height);
  }
  await expect(panel.getByText('pronta', { exact: true })).toBeVisible();

  await expectNoAxeViolations(page);
});

test('the map shows each seated table\'s total, and a table too long without a round in red', async ({ page }) => {
  await atM2OnMap(page);

  // The times are minutes before the spec loaded: a slow run may add a minute.
  await expect(page.getByRole('button', { name: /^Mesa M2 / })).toHaveAccessibleName(/^Mesa M2 Ocupada 3 pessoas · 3\d min 1530,00\sMTn$/);
  const m3 = page.getByRole('button', { name: /^Mesa M3 / });
  await expect(m3).toHaveAccessibleName(/^Mesa M3 Ocupada 2 pessoas · 1 h 1\d min 480,00\sMTn [56]\d min sem ronda$/);
  await expect(m3).toHaveClass(/bg-destructive-tint/);
  await expect(page.getByRole('button', { name: /^Mesa M4 / })).toHaveAccessibleName(/2010,00\sMTn Conta pedida$/);
});

test('screens of the table map, M2 selected, at 1024 × 768 and 1440 × 900', async ({ page }) => {
  const dir = process.env.E2E_SCREENS;
  test.skip(!dir, 'E2E_SCREENS not set');
  await atM2OnMap(page);
  await expect(page.getByText('pronta', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: /sem ronda/ })).toBeVisible();
  for (const [width, height] of [
    [1024, 768],
    [1440, 900],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${dir}/tables-${width}x${height}.png`, animations: 'disabled' });
  }
});

// "Pedir" (ticket 12): its answers in the built app, where jsdom cannot check the 56 px targets or the layout.
const REPRICED = {
  ...ORDER,
  version: 5,
  lines: LINES.map((line) => (line.id === id(801) ? { ...line, gross: { amount: 150, currency: 'MZN' } } : line)),
};

async function pedirAnswers(page: Page, answer: 'unavailable' | 'prices-changed') {
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}/batches`, async (route) => {
    if (answer === 'unavailable') return route.fulfill({ status: 503, contentType: 'application/problem+json', json: { status: 503, title: 'Service Unavailable', code: 'BACKEND_UNAVAILABLE' } });
    // The server repriced the drafts before refusing the round: the order read again carries the new gross.
    await page.unroute(`**/api/v1/pos/orders/${ORDER_ID}`);
    await page.route(`**/api/v1/pos/orders/${ORDER_ID}`, (r) => r.fulfill({ json: REPRICED }));
    return route.fulfill({ status: 409, contentType: 'application/problem+json', json: { status: 409, title: 'Conflict', code: 'PRICES_CHANGED' } });
  });
}

test('"Pedir" without an answer keeps the round unsent, with touch-sized "Tentar novamente" and "Descartar"', async ({ page }) => {
  await atOrder(page);
  await pedirAnswers(page, 'unavailable');
  const panel = page.getByRole('complementary', { name: 'Pedido' });
  const before = await box(page.getByRole('group', { name: 'Total' }));

  await page.getByRole('button', { name: 'Pedir' }).click();

  await expect(panel.getByRole('alert')).toHaveText('Sem ligação — a ronda não foi confirmada.');
  await expect(panel.getByRole('list', { name: 'Por enviar' }).getByRole('listitem')).toHaveCount(3);
  const panelBox = await box(panel);
  for (const name of ['Tentar novamente', 'Descartar']) {
    const button = await box(panel.getByRole('button', { name }));
    expect(button.height).toBeGreaterThanOrEqual(56);
    expect(button.x + button.width).toBeLessThanOrEqual(panelBox.x + panelBox.width);
  }
  // Nothing above the action moved.
  expect(await box(page.getByRole('group', { name: 'Total' }))).toEqual(before);
  await expectNoAxeViolations(page);
  if (process.env.E2E_SCREENS) {
    await page.screenshot({ path: `${process.env.E2E_SCREENS}/pedir-unknown-1024x768.png`, animations: 'disabled' });
  }
});

test('"Pedir" refused for new prices shows each changed price beside the one shown before', async ({ page }) => {
  await atOrder(page);
  await pedirAnswers(page, 'prices-changed');
  const panel = page.getByRole('complementary', { name: 'Pedido' });

  await page.getByRole('button', { name: 'Pedir' }).click();

  await expect(panel.getByRole('alert')).toHaveText('Os preços mudaram. Reveja os novos valores e carregue em Pedir de novo.');
  const water = panel.getByRole('list', { name: 'Por enviar' }).getByRole('listitem').first();
  await expect(water).toContainText('Preço alterado, antes 120,00');
  await expect(panel.getByRole('button', { name: 'Pedir' })).toBeEnabled();
  await expectNoAxeViolations(page);
  if (process.env.E2E_SCREENS) {
    await page.screenshot({ path: `${process.env.E2E_SCREENS}/pedir-prices-changed-1024x768.png`, animations: 'disabled' });
  }
});
