import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { saveScreens } from './screens';
import { id, logIn, SESSION, standInDevice, STORE } from './stand-in';

// The reservations screen in the built POS, its API stood in by page.route (the preview has no backend): what jsdom
// cannot check, axe on the rendered screen and its "Nova reserva" dialog, the touch targets, and a booking's request.

/** Friday 2026-11-20, 12:00 in the store (Africa/Maputo). */
const NOW = '2026-11-20T10:00:00Z';

const MANAGER = {
  ...SESSION,
  permissions: [
    ...SESSION.permissions,
    `PERM_RESERVATION_VIEW@${STORE}`,
    `PERM_RESERVATION_MANAGE@${STORE}`,
    `PERM_CUSTOMER_VIEW@${STORE}`,
    `PERM_CUSTOMER_MANAGE@${STORE}`,
  ],
};

const table = (n: number, code: string, fields: object = {}) => ({
  id: id(n),
  code,
  seats: 4,
  status: 'FREE',
  locked: false,
  subtotalPrinted: false,
  reserved: false,
  guests: null,
  openedAt: null,
  openOrderId: null,
  openedByEmployeeId: null,
  nextReservationAt: null,
  ...fields,
});
const MAP = {
  storeId: STORE,
  areas: [
    { id: id(900), code: 'SALA', name: 'Sala', tables: [table(1, 'M1'), table(2, 'M2', { status: 'OCCUPIED', guests: 2 })] },
    { id: id(901), code: 'ESPLANADA', name: 'Esplanada', tables: [table(10, 'E1', { seats: 2 })] },
  ],
};

const reservation = (n: number, fields: object) => ({
  id: id(n),
  storeId: STORE,
  status: 'BOOKED',
  durationMinutes: 120,
  version: 1,
  tableIds: [],
  ...fields,
});
// In the backend's order (ORDER BY starts_at): the screen shows the list as the server sends it.
const DAY = [
  reservation(303, { status: 'NO_SHOW', startsAt: '2026-11-20T08:00:00Z', partySize: 2, contactName: 'Pedro Miranda' }),
  reservation(301,{ status: 'SEATED', startsAt: '2026-11-20T09:30:00Z', partySize: 3, contactName: 'Família Costa', tableIds: [id(2)] }),
  reservation(302, {
    startsAt: '2026-11-20T17:30:00Z',
    partySize: 4,
    contactName: 'Ana Ferreira',
    contactPhoneDigits: '841234567',
    notes: 'Cadeira de bebé',
    tableIds: [id(1), id(10)],
  }),
  reservation(304, {
    status: 'CANCELLED',
    startsAt: '2026-11-20T18:00:00Z',
    partySize: 5,
    contactName: 'Carla Lopes',
    cancelReason: 'O cliente telefonou',
  }),
];

async function standIn(page: Page) {
  const booked: unknown[] = [];
  await page.clock.setFixedTime(new Date(NOW));
  await standInDevice(page);
  // Registered after standInDevice, so it wins: a waiter who may manage reservations and customers.
  await page.route('**/api/v1/auth/pos-login', (route) => route.fulfill({ json: MANAGER }));
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: MAP }));
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route(/\/api\/v1\/pos\/reservations(\?.*)?$/, async (route) => {
    if (route.request().method() === 'GET') return route.fulfill({ json: DAY });
    booked.push(route.request().postDataJSON());
    await route.fulfill({ json: reservation(399, { startsAt: '2026-11-20T18:30:00Z', partySize: 2, contactName: 'Rui Sousa' }) });
  });
  return { booked };
}

async function atReservations(page: Page) {
  const backend = await standIn(page);
  await logIn(page);
  await page.getByRole('navigation', { name: 'Secções' }).getByRole('link', { name: 'Reservas' }).click();
  await expect(page.getByRole('article', { name: 'Ana Ferreira, 19:30' })).toBeVisible();
  return backend;
}

test('the reservations screen and its "Nova reserva" dialog are accessible', async ({ page }) => {
  await atReservations(page);
  await page.getByRole('article', { name: 'Ana Ferreira, 19:30' }).getByRole('button', { name: 'Ana Ferreira' }).click();
  await expect(page.getByRole('complementary', { name: 'Detalhe da reserva' }).getByRole('heading', { name: 'Ana Ferreira' })).toBeVisible();
  await expectNoAxeViolations(page);

  await page.getByRole('button', { name: 'Nova reserva' }).click();
  await expect(page.getByRole('dialog', { name: 'Nova reserva' })).toBeVisible();
  await expectNoAxeViolations(page);
});

test('the commands are touch-sized, and the screen does not overflow the smallest POS viewport', async ({ page }) => {
  await atReservations(page);

  expect((await page.getByRole('button', { name: 'Nova reserva' }).boundingBox())!.height).toBeGreaterThanOrEqual(56);
  const ana = page.getByRole('article', { name: 'Ana Ferreira, 19:30' });
  expect((await ana.getByRole('button', { name: 'Sentar' }).boundingBox())!.height).toBeGreaterThanOrEqual(48);
  expect((await ana.getByRole('button', { name: 'Ana Ferreira' }).boundingBox())!.height).toBeGreaterThanOrEqual(48);
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);
});

test('a reservation booked in the dialog is sent as the store time, with its party, table and contact', async ({ page }) => {
  const { booked } = await atReservations(page);

  await page.getByRole('button', { name: 'Nova reserva' }).click();
  const form = page.getByRole('dialog', { name: 'Nova reserva' });
  await form.getByLabel('Hora').fill('20:30');
  await form.getByRole('button', { name: '2 pessoas', exact: true }).click();
  await form.getByRole('checkbox', { name: /^E1/ }).check();
  await form.getByRole('textbox', { name: 'Nome' }).fill('Rui Sousa');
  await form.getByRole('textbox', { name: 'Telefone' }).fill('84 555 1234');
  await form.getByRole('button', { name: 'Guardar reserva' }).click();

  await expect(form).toBeHidden();
  expect(booked).toEqual([
    {
      startsAt: '2026-11-20T18:30:00.000Z',
      partySize: 2,
      contactName: 'Rui Sousa',
      contactPhone: '84 555 1234',
      tableIds: [id(10)],
    },
  ]);
});

test('screenshots of the screen and its dialog (E2E_SCREENS)', async ({ page }) => {
  test.skip(!process.env.E2E_SCREENS, 'screenshots only on request');
  await atReservations(page);
  await page.getByRole('article', { name: 'Ana Ferreira, 19:30' }).getByRole('button', { name: 'Ana Ferreira' }).click();
  await saveScreens(page, 'reservations');
  await page.getByRole('button', { name: 'Nova reserva' }).click();
  await expect(page.getByRole('dialog', { name: 'Nova reserva' })).toBeVisible();
  await saveScreens(page, 'nova-reserva', { fullPage: false });
});
