import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, SESSION, standInDevice, STORE, tile } from './stand-in';

// "Conta & Pagamento" in the built POS, its API stood in by page.route: what jsdom cannot check — the three columns'
// real geometry at the smallest POS viewport (DS: 1024 × 768), the touch targets, axe on the screen and its states,
// and one bill from the map to its close. With E2E_SCREENS set, the screen is also saved there at 1440, 1024 and 390.

const mzn = (amount: string) => ({ amount, currency: 'MZN' });
const ORDER_ID = id(500);
const CASHIER = {
  ...SESSION,
  permissions: ['BILL_SPLIT', 'PAYMENT_COLLECT', 'FISCAL_DOCUMENT_REPRINT', 'DINING_VIEW', 'ORDER_EDIT'].map(
    (permission) => `PERM_${permission}@${STORE}`,
  ),
};

const MAP = {
  storeId: STORE,
  areas: [
    {
      id: id(900),
      code: 'SALA',
      name: 'Sala',
      tables: [
        { id: id(1), code: 'M1', seats: 4, status: 'FREE', locked: false, subtotalPrinted: false, reserved: false },
        {
          id: id(2),
          code: 'M2',
          seats: 4,
          status: 'OCCUPIED',
          locked: false,
          subtotalPrinted: false,
          reserved: false,
          guests: 3,
          openedAt: '2026-11-18T10:05:00Z',
          openOrderId: ORDER_ID,
        },
      ],
    },
  ],
};

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
  lines: [],
  batches: [],
  due: mzn('1530.00'),
};

const coverage = (lineId: number, description: string, netQty: string, gross: string) => ({
  lineId: id(lineId),
  description,
  netQty,
  gross: mzn(gross),
  partitioned: '0.000',
  issued: '0.000',
  drafted: '0.000',
});

const BILL = {
  orderId: ORDER_ID,
  storeId: STORE,
  lifecycle: 'OPEN',
  orderVersion: 4,
  due: mzn('1530.00'),
  paid: mzn('0.00'),
  balance: mzn('1530.00'),
  credit: mzn('0.00'),
  residualBalance: mzn('1530.00'),
  financialState: 'UNSETTLED',
  fiscalState: 'NONE',
  // The server writes a record's empty fields as null, never leaves them out.
  documentId: null,
  paymentStatus: 'UNPAID',
  openAttempts: [],
  partitions: [],
  coverage: [coverage(803, 'Bitoque', '3.000', '1350.00'), coverage(804, 'Cerveja 2M', '2.000', '180.00')],
};

const part = (n: number, fields: object = {}) => ({
  id: id(1000 + n),
  orderId: ORDER_ID,
  kind: 'AMOUNT',
  status: 'LIVE',
  partNo: n,
  parts: 3,
  label: null,
  lines: [],
  due: mzn('510.00'),
  paid: mzn('0.00'),
  credit: mzn('0.00'),
  balance: mzn('510.00'),
  financialState: 'UNSETTLED',
  paymentStatus: 'UNPAID',
  invoiced: false,
  documentId: null,
  version: 0,
  ...fields,
});

/** Split in three, the first part paid; a card payment of the second still in doubt. */
const SPLIT = {
  ...BILL,
  orderVersion: 5,
  paid: mzn('510.00'),
  balance: mzn('1020.00'),
  residualBalance: mzn('0.00'),
  financialState: 'PARTIALLY_SETTLED',
  paymentStatus: 'PARTIALLY_PAID',
  partitions: [part(1, { paid: mzn('510.00'), balance: mzn('0.00'), financialState: 'SETTLED', paymentStatus: 'PAID' }), part(2), part(3)],
  openAttempts: [
    { id: id(1100), orderId: ORDER_ID, methodCode: 'MPESA', methodKind: 'INTEGRATED', status: 'UNKNOWN', amount: mzn('510.00'), targetType: 'PARTITION', partitionId: id(1002) },
  ],
};

const PAID = { ...BILL, paid: mzn('1530.00'), balance: mzn('0.00'), residualBalance: mzn('0.00'), financialState: 'SETTLED', paymentStatus: 'PAID' };
const ISSUED = { ...PAID, orderVersion: 5, fiscalState: 'ISSUED', documentId: id(1200) };

/** The bill as the stand-in server has it; each command moves it on. */
async function standIn(page: Page, bill: object = BILL) {
  const state = { bill };
  await standInDevice(page);
  // Registered last, so it answers first: a cashier, who may split, charge and close.
  await page.route('**/api/v1/auth/pos-login', (route) => route.fulfill({ json: CASHIER }));
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: MAP }));
  // The stream stays pending: this screen does not need a change.
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}`, (route) => route.fulfill({ json: ORDER }));
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}/settlement`, (route) => route.fulfill({ json: state.bill }));
  await page.route('**/api/v1/pos/payment-attempts', (route) => {
    state.bill = PAID;
    return route.fulfill({
      status: 201,
      json: { id: id(1101), orderId: ORDER_ID, methodCode: 'CASH', methodKind: 'CASH', status: 'SUCCEEDED', amount: mzn('1530.00'), tendered: mzn('2000.00'), change: mzn('470.00'), targetType: 'ORDER' },
    });
  });
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}/documents`, (route) => {
    state.bill = ISSUED;
    return route.fulfill({ status: 201, json: { id: id(1200), orderId: ORDER_ID, status: 'ISSUED', seriesCode: 'A', number: 42, totalGross: mzn('1530.00') } });
  });
  await page.route(`**/api/v1/pos/orders/${ORDER_ID}/close`, (route) => {
    state.bill = { ...ISSUED, lifecycle: 'CLOSED' };
    return route.fulfill({ json: state.bill });
  });
}

async function atBill(page: Page, bill: object = BILL) {
  await standIn(page, bill);
  await logIn(page);
  await tile(page, 'M2').click();
  await page.getByRole('complementary', { name: 'Detalhe da mesa' }).getByRole('link', { name: 'Subtotal' }).click();
  await expect(page.getByRole('heading', { name: 'Conta da mesa M2' })).toBeVisible();
  await expect(page.getByRole('group', { name: 'Resumo da conta' })).toBeVisible();
}

const box = async (locator: ReturnType<Page['getByRole']>) => (await locator.boundingBox())!;

test('at 1024 × 768 the three columns sit side by side, nothing scrolls sideways, and targets are touch-sized', async ({ page }) => {
  await atBill(page);

  const split = await box(page.getByRole('region', { name: 'Divisão da conta' }));
  const pay = await box(page.getByRole('region', { name: 'Pagamento' }));
  const closing = await box(page.getByRole('region', { name: 'Documento e fecho' }));
  expect(split.x + split.width).toBeLessThanOrEqual(pay.x);
  expect(pay.x + pay.width).toBeLessThanOrEqual(closing.x);
  expect(closing.x + closing.width).toBeLessThanOrEqual(1024);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);

  // The primary actions: 56 px.
  for (const name of [/^Cobrar 1530,00/, /^Fechar conta$/]) {
    expect((await box(page.getByRole('button', { name }))).height).toBeGreaterThanOrEqual(56);
  }
  for (const target of await page.getByRole('main').getByRole('button').all()) {
    if (!(await target.isVisible())) continue;
    const { width, height } = (await target.boundingBox())!;
    expect(Math.min(width, height)).toBeGreaterThanOrEqual(48);
  }
});

test('the bill is accessible: as it opens, split by items, and with a NIF typed', async ({ page }) => {
  await atBill(page);
  await expectNoAxeViolations(page);

  await page.getByRole('tab', { name: 'Por artigos' }).click();
  await expect(page.getByRole('list', { name: 'Artigos por dividir' })).toBeVisible();
  await expectNoAxeViolations(page);

  // The tile is the label a finger taps; the radio inside it is visually hidden.
  await page.getByText('Com NIF', { exact: true }).click();
  await expect(page.getByRole('radio', { name: 'Com NIF' })).toBeChecked();
  await page.getByLabel('NIF', { exact: true }).fill('400123456');
  await expectNoAxeViolations(page);
});

test('a split bill with a payment in doubt is accessible, and its parts are touch-sized', async ({ page }) => {
  await atBill(page, SPLIT);
  await expect(page.getByRole('region', { name: 'Pagamentos em curso' })).toContainText('Por confirmar — reconciliação pendente');
  await page.getByRole('button', { name: 'Cobrar Parte 3 de 3' }).click();
  await expect(page.getByRole('heading', { name: 'Valor a cobrar — Parte 3 de 3' })).toBeVisible();

  await expectNoAxeViolations(page);
  for (const target of await page.getByRole('list', { name: 'Divisões' }).getByRole('button').all()) {
    const { width, height } = (await target.boundingBox())!;
    expect(Math.min(width, height)).toBeGreaterThanOrEqual(48);
  }
});

test('one bill from the map to its close: cash with change, the document, the close, back on the map', async ({ page }) => {
  await atBill(page);

  await page.getByLabel('Valor recebido').fill('2000');
  await page.getByRole('button', { name: /^Cobrar 1530,00/ }).click();
  await expect(page.getByRole('status', { name: 'Último pagamento' })).toContainText('Troco: 470,00');
  await expect(page.getByRole('group', { name: 'Resumo da conta' })).toContainText('0,00');

  await page.getByRole('button', { name: 'Emitir documento' }).click();
  await expect(page.getByRole('status', { name: 'Documento' })).toHaveText('Documento A/42 emitido.');
  await page.getByRole('button', { name: 'Fechar conta' }).click();

  await expect(page).toHaveURL(/\/tables$/);
});

test('screens for the PR, at 1440, 1024 and 390 wide', async ({ page }) => {
  const dir = process.env.E2E_SCREENS;
  test.skip(!dir, 'E2E_SCREENS not set');
  await atBill(page, SPLIT);
  for (const [width, height] of [
    [1440, 1024],
    [1024, 768],
    [390, 844],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.getByRole('button', { name: 'Cobrar Parte 3 de 3' }).click();
    await page.screenshot({ path: `${dir}/payment-${width}.png`, animations: 'disabled', fullPage: true });
    await page.getByRole('button', { name: 'Cobrar Parte 3 de 3' }).click();
  }
});
