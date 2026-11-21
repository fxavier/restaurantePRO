import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, SESSION, standInDevice, STORE } from './stand-in';

// The cash screen ("Caixa & Turno") in the built POS, its API stood in by page.route (the preview has no backend):
// what jsdom cannot check, its touch targets and layout at the POS viewports, and axe on the screen and its dialogs.
// With E2E_SCREENS set, the screens are saved there at 1440, 1024 and 390 px wide, to compare with Stitch.

const money = (amount: string) => ({ amount, currency: 'MZN' });
const DAY = { id: id(810), storeId: STORE, businessDate: '2026-11-19', status: 'OPEN', version: 4, earlierOpenDays: [] };
const REGISTERS = {
  content: [
    { id: id(801), code: 'C01', name: 'Caixa Principal', storeId: STORE, active: true, version: 1 },
    { id: id(802), code: 'C02', name: 'Caixa Bar', storeId: STORE, active: true, version: 1 },
  ],
  page: 0,
  size: 100,
  totalElements: 2,
};
const OPEN_SESSION = {
  id: id(820),
  storeId: STORE,
  registerId: id(801),
  cashDayId: DAY.id,
  businessDate: DAY.businessDate,
  operatorEmployeeId: SESSION.employeeId,
  status: 'OPEN',
  openedAt: '2026-11-19T06:30:00Z',
  openingFloat: money('1500.00'),
  expected: [
    { methodCode: 'CASH', amount: money('4875.50') },
    { methodCode: 'CARD', amount: money('3210.40') },
  ],
  warnings: [],
  version: 2,
};
const CLOSING = {
  id: id(830),
  storeId: STORE,
  registerId: id(801),
  cashDayId: DAY.id,
  dayRound: 1,
  attemptNo: 1,
  status: 'DRAFT',
  version: 0,
  lines: [
    { methodCode: 'CASH', expected: money('4875.50'), counted: money('4850.50'), difference: money('-25.00') },
    { methodCode: 'CARD', expected: money('3210.40'), counted: money('3210.40'), difference: money('0.00') },
  ],
};

/** A cashier who may also close the day. */
const CASHIER = {
  ...SESSION,
  permissions: ['CASH_OPERATE', 'CASH_VIEW', 'CASH_DAY_CLOSE'].map((name) => `PERM_${name}@${STORE}`),
};

/** The device, the cashier's login, an empty table map and the cash API, the session held as the server would. */
async function standIn(page: Page, { open }: { open: boolean }) {
  let session: object | null = open ? OPEN_SESSION : null;
  await standInDevice(page);
  // Registered last, so it answers before the stand-in's waiter.
  await page.route('**/api/v1/auth/pos-login', (route) => route.fulfill({ json: CASHIER }));
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: { storeId: STORE, areas: [] } }));
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/cash/registers**', (route) => route.fulfill({ json: REGISTERS }));
  await page.route('**/api/v1/pos/cash/days/current', (route) => route.fulfill({ json: DAY }));
  await page.route('**/api/v1/pos/cash/sessions/current', (route) =>
    session
      ? route.fulfill({ json: session })
      : route.fulfill({ status: 404, contentType: 'application/problem+json', json: { status: 404, code: 'NOT_FOUND', title: 'Not Found' } }),
  );
  await page.route('**/api/v1/pos/cash/sessions', (route) => {
    session = OPEN_SESSION;
    return route.fulfill({ status: 201, json: OPEN_SESSION });
  });
  await page.route('**/api/v1/pos/cash/closings', (route) => route.fulfill({ json: CLOSING }));
}

async function atCash(page: Page) {
  await logIn(page);
  await page.getByRole('navigation', { name: 'Secções' }).getByRole('link', { name: 'Caixa & Turno' }).click();
}

const sessionCard = (page: Page) => page.getByRole('region', { name: /^Caixa Principal/ });

test('the open session, its blind count and a movement dialog are accessible and touch-sized', async ({ page }) => {
  await standIn(page, { open: true });
  await atCash(page);
  await expect(sessionCard(page)).toBeVisible();
  await expect(page.getByRole('form', { name: 'Contagem cega' })).toBeVisible();
  await expect(page.getByRole('region', { name: 'Fecho operacional do dia' })).toBeVisible();

  for (const button of await page.getByRole('main').getByRole('button').all()) {
    expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(48);
  }
  expect((await page.getByRole('button', { name: 'Fechar sessão' }).boundingBox())!.height).toBeGreaterThanOrEqual(56);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);
  await expectNoAxeViolations(page);

  await sessionCard(page).getByRole('button', { name: 'Despesa autorizada' }).click();
  await expect(page.getByRole('dialog', { name: 'Despesa autorizada' })).toBeVisible();
  await expectNoAxeViolations(page);
});

test('without a session: opening one, and the closing of a register, are accessible', async ({ page }) => {
  await standIn(page, { open: false });
  await atCash(page);
  const closing = page.getByRole('region', { name: 'Fecho da caixa' });
  await closing.getByRole('radio', { name: /Caixa Principal/ }).check();
  await closing.getByRole('button', { name: 'Preparar fecho' }).click();
  await expect(closing.getByRole('table', { name: 'Diferenças por meio de pagamento' })).toBeVisible();
  await expectNoAxeViolations(page);

  const form = page.getByRole('form', { name: 'Abrir sessão de caixa' });
  await form.getByRole('radio', { name: /Caixa Principal/ }).check();
  await form.getByLabel('Fundo de caixa inicial').fill('1500,00');
  await form.getByRole('button', { name: 'Abrir sessão' }).click();
  await expect(sessionCard(page)).toBeVisible();
});

test('the blind count close asks for a confirmation, which is accessible', async ({ page }) => {
  await standIn(page, { open: true });
  await atCash(page);
  const count = page.getByRole('form', { name: 'Contagem cega' });
  await count.getByLabel(/^Numerário/).fill('4850,50');
  await count.getByLabel(/^CARD/).fill('3210,40');
  await count.getByRole('button', { name: 'Fechar sessão' }).click();
  await expect(page.getByRole('alertdialog', { name: 'Fechar a sessão de caixa?' })).toBeVisible();

  await expectNoAxeViolations(page);
});

test('screens for the PR, at 1440, 1024 and 390 px wide', async ({ page }) => {
  const dir = process.env.E2E_SCREENS;
  test.skip(!dir, 'E2E_SCREENS not set');
  await standIn(page, { open: true });
  await atCash(page);
  await expect(sessionCard(page)).toBeVisible();
  for (const [width, height] of [
    [1440, 1000],
    [1024, 768],
    [390, 844],
  ] as const) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${dir}/cash-session-${width}.png`, animations: 'disabled', fullPage: true });
    await sessionCard(page).getByRole('button', { name: 'Despesa autorizada' }).click();
    await page.screenshot({ path: `${dir}/cash-expense-${width}.png`, animations: 'disabled' });
    await page.getByRole('dialog').getByRole('button', { name: 'Cancelar' }).click();
  }
});
