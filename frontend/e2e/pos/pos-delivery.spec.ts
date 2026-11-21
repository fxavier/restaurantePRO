import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, SESSION, standInDevice, STORE } from './stand-in';

// The takeaway and delivery section in the built POS, its API stood in by page.route (the preview has no backend):
// axe on the dispatch board and on the new-order dialog, the board's touch targets, and (with E2E_SCREENS set)
// screenshots to compare with the Stitch screens.

const address = (line1: string, line2?: string) => ({ line1, line2, city: 'Maputo' });
const delivery = (n: number, fields: object) => ({
  orderId: id(n),
  orderNumber: n - 1000,
  storeId: STORE,
  customerId: id(1201),
  version: 0,
  zoneId: id(1001),
  zoneCode: 'Z1',
  phone: '841234567',
  history: [],
  ...fields,
});

const BOARD = [
  delivery(1082, { status: 'PENDING', address: address('Av. Julius Nyerere 1200', '3.º andar'), promisedAt: '2026-11-18T18:15:00Z' }),
  delivery(1085, { status: 'OUT_FOR_DELIVERY', version: 1, zoneCode: 'Z2', address: address('Rua da Mesquita 45') }),
  delivery(1079, {
    status: 'RETURNED',
    version: 2,
    address: address('Rua do Bagamoyo 10'),
    history: [{ id: id(1302), fromStatus: 'OUT_FOR_DELIVERY', toStatus: 'RETURNED', reason: 'Cliente ausente' }],
  }),
];

const ZONES = [
  { id: id(1001), storeId: STORE, code: 'Z1', name: 'Polana', active: true, feeItemId: id(707), version: 0 },
  { id: id(1002), storeId: STORE, code: 'Z2', name: 'Sommerschield', active: true, feeItemId: id(707), version: 0 },
];

const MARTA = {
  id: id(1201),
  name: 'Marta Fontes',
  active: true,
  version: 0,
  phones: [{ id: id(1211), digits: '841234567', primary: true }],
  addresses: [
    { id: id(1221), line1: 'Av. Julius Nyerere 1200', line2: '3.º andar', city: 'Maputo', version: 0 },
    { id: id(1222), line1: 'Rua do Bagamoyo 10', city: 'Maputo', version: 0 },
  ],
};

async function standIn(page: Page) {
  await standInDevice(page);
  // Ana may run deliveries here (the shared stand-in's session may not).
  await page.route('**/api/v1/auth/pos-login', (route) =>
    route.fulfill({ json: { ...SESSION, permissions: [...SESSION.permissions, `PERM_DELIVERY_OPERATE@${STORE}`] } }),
  );
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: { storeId: STORE, areas: [] } }));
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/deliveries', (route) => route.fulfill({ json: BOARD }));
  await page.route('**/api/v1/pos/delivery-zones', (route) => route.fulfill({ json: ZONES }));
  await page.route('**/api/v1/pos/customers?*', (route) =>
    route.fulfill({ json: [{ id: MARTA.id, name: MARTA.name, active: true, phones: ['841234567'] }] }),
  );
  await page.route(`**/api/v1/pos/customers/${MARTA.id}`, (route) => route.fulfill({ json: MARTA }));
}

async function atDelivery(page: Page) {
  await standIn(page);
  await logIn(page);
  await page.getByRole('navigation', { name: 'Secções' }).getByRole('link', { name: 'Takeaway & Delivery' }).click();
  await expect(page.getByRole('article', { name: 'Entrega n.º 82' })).toBeVisible();
}

async function screens(page: Page, name: string) {
  if (!process.env.E2E_SCREENS) return;
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
    await page.screenshot({ path: test.info().outputPath(`${name}-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1024, height: 768 });
}

test('the dispatch board is accessible, and its commands are touch-sized', async ({ page }) => {
  await atDelivery(page);

  for (const name of ['Expedir', 'Entregue', 'Devolvida', 'Novo pedido']) {
    const { height } = (await page.getByRole('button', { name, exact: true }).first().boundingBox())!;
    expect(height).toBeGreaterThanOrEqual(48);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);
  await expectNoAxeViolations(page);
  await screens(page, 'delivery-board');
});

test('the new delivery dialog is accessible once filled in', async ({ page }) => {
  await atDelivery(page);
  await page.getByRole('button', { name: 'Novo pedido' }).click();
  const dialog = page.getByRole('dialog', { name: 'Novo pedido fora de sala' });
  await expectNoAxeViolations(page);

  await dialog.getByRole('radio', { name: /^Delivery/ }).check();
  await dialog.getByLabel('Nome ou telefone do cliente').fill('Marta');
  await dialog.getByRole('button', { name: 'Procurar' }).click();
  await dialog.getByRole('button', { name: /^Marta Fontes/ }).click();
  await dialog.getByRole('radio', { name: 'Av. Julius Nyerere 1200, 3.º andar, Maputo' }).check();
  await dialog.getByRole('radio', { name: /^Polana/ }).check();
  await dialog.getByRole('radio', { name: '30 min' }).check();

  await expect(dialog.getByRole('button', { name: 'Criar entrega' })).toBeEnabled();
  await expectNoAxeViolations(page);
  await screens(page, 'delivery-new-order');
});
