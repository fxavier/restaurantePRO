import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

// The stores page and the dining page of the built BackOffice, their API stood in by page.route (the preview has no
// BFF): the company profile and store cards, the areas with their tables, the reservation settings and the day's
// reservations, with axe on each screen and on its main dialog. With E2E_SCREENS=<dir>, screenshots at 1440 and 390
// wide land there (compared by hand with the Stitch design).

const TENANT = 'aaaaaaaa-0000-4000-8000-000000000001';
const BAIXA = '11111111-0000-4000-8000-000000000001';
const SOMM = '22222222-0000-4000-8000-000000000002';
const SALA = 'a1a1a1a1-0000-4000-8000-000000000001';
const ESPL = 'a2a2a2a2-0000-4000-8000-000000000002';
const session = {
  kind: 'ACCOUNT',
  name: 'Ana Admin',
  permissions: ['PERM_STORE_MANAGE', 'PERM_COMPANY_MANAGE', 'PERM_DINING_MANAGE', 'PERM_TERMINAL_MANAGE'],
  tenantId: TENANT,
  tenantName: 'Restaurante A, Lda',
};
const tenants = [{ tenantId: TENANT, code: 'rest-a', legalName: 'Restaurante A, Lda', kind: 'ACCOUNT', status: 'ACTIVE' }];
const stores = [
  { id: BAIXA, code: 'BAIXA', name: 'Loja Baixa', active: true, timezone: 'Africa/Maputo', businessDayCutoff: '05:00:00', currency: 'MZN', version: 0 },
  { id: SOMM, code: 'SOMM', name: 'Loja Sommerschield', active: false, timezone: 'Africa/Maputo', businessDayCutoff: '04:30:00', currency: 'MZN', version: 3 },
];
const profile = { name: 'Restaurante A, Lda', taxId: '400123456', country: 'MZ', currency: 'MZN', version: 3 };
const areas = [
  { id: SALA, storeId: BAIXA, code: 'SALA', name: 'Sala principal', active: true, version: 0 },
  { id: ESPL, storeId: BAIXA, code: 'ESPL', name: 'Esplanada', active: true, version: 0 },
];
const table = (id: string, areaId: string, code: string, seats: number, extra = {}) => ({
  id: `b0b0b0b0-0000-4000-8000-0000000000${id}`,
  storeId: BAIXA,
  areaId,
  code,
  seats,
  active: true,
  locked: false,
  version: 0,
  ...extra,
});
const tables = [
  table('01', SALA, 'M01', 4),
  table('02', SALA, 'M02', 2, { locked: true }),
  table('03', SALA, 'M03', 6),
  table('04', ESPL, 'E01', 4),
  table('05', ESPL, 'E02', 8, { active: false }),
];
const reservations = [
  {
    id: 'c1c1c1c1-0000-4000-8000-000000000001',
    storeId: BAIXA,
    startsAt: '2026-11-20T18:30:00Z',
    partySize: 4,
    contactName: 'Joana Mabote',
    status: 'BOOKED',
    tableIds: [tables[0]!.id],
    version: 0,
  },
  {
    id: 'c2c2c2c2-0000-4000-8000-000000000002',
    storeId: BAIXA,
    startsAt: '2026-11-20T19:00:00Z',
    partySize: 2,
    contactName: 'Carlos Nhantumbo',
    status: 'SEATED',
    tableIds: [tables[3]!.id],
    version: 0,
  },
];
const page200 = <T>(content: T[]) => ({ content, page: 0, size: 200, totalElements: content.length });

async function standIn(page: Page) {
  await page.route('**/api/v1/auth/session', (route) => route.fulfill({ json: session }));
  await page.route('**/api/v1/auth/tenants', (route) => route.fulfill({ json: tenants }));
  await page.route('**/api/v1/backoffice/stores?**', (route) => route.fulfill({ json: { ...page200(stores), size: 100 } }));
  await page.route('**/api/v1/backoffice/company-profile', (route) => route.fulfill({ json: profile }));
  await page.route('**/api/v1/backoffice/areas?**', (route) => route.fulfill({ json: page200(areas) }));
  await page.route('**/api/v1/backoffice/tables?**', (route) => route.fulfill({ json: page200(tables) }));
  await page.route('**/api/v1/backoffice/dining/settings/*', (route) =>
    route.fulfill({ json: { storeId: BAIXA, reservationWindowMinutes: 120, noShowGraceMinutes: 30, version: 2 } }),
  );
  await page.route('**/api/v1/backoffice/reservations?**', (route) => route.fulfill({ json: reservations }));
}

/** With E2E_SCREENS set, the full page at Stitch's desktop width and at a phone's. */
async function screens(page: Page, name: string) {
  const dir = process.env.E2E_SCREENS;
  if (!dir) return;
  const before = page.viewportSize()!;
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
    await page.screenshot({ path: `${dir}/${name}-${width}.png`, fullPage: true });
  }
  await page.setViewportSize(before);
}

test('stores: the company profile and the store cards, and the new-store dialog, axe clean', async ({ page }) => {
  await standIn(page);

  await page.goto('/organization/stores');
  await expect(page.getByRole('region', { name: 'Restaurante A, Lda' })).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(2);
  await expect(page.getByRole('article', { name: 'Loja Sommerschield' }).getByText('Inativa')).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, 'bo-stores');

  await page.getByRole('button', { name: 'Nova loja' }).click();
  const dialog = page.getByRole('dialog', { name: 'Nova loja' });
  await expect(dialog.getByLabel('Fuso horário')).toHaveValue('Africa/Maputo');
  await expectNoAxeViolations(page);
  await dialog.getByRole('button', { name: 'Criar loja' }).click();
  await expect(dialog.getByText('Indique o código.')).toBeVisible();
  await expectNoAxeViolations(page);
});

test('dining: areas with their tables, settings and reservations, and the edit-table dialog, axe clean', async ({ page }) => {
  await standIn(page);

  await page.goto(`/organization/dining?storeId=${BAIXA}`);
  const sala = page.getByRole('article', { name: 'Sala principal' });
  await expect(sala.getByRole('list', { name: 'Mesas de Sala principal' }).getByRole('listitem')).toHaveCount(3);
  await expect(page.getByLabel('Janela de reserva (minutos)')).toHaveValue('120');
  await expect(page.getByRole('region', { name: 'Reservas do dia' })).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, 'bo-dining');

  await page.getByRole('button', { name: /^Editar mesa M01/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Editar mesa' });
  await expect(dialog.getByLabel('Lugares')).toHaveValue('4');
  await expect(dialog.getByLabel('Área')).toHaveValue(SALA);
  await expectNoAxeViolations(page);
});
