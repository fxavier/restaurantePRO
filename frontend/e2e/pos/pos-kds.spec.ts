import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, SESSION, standInDevice, STORE } from './stand-in';

// The kitchen display in the built POS, its API stood in by page.route (the preview has no backend): axe on the board
// and on the history, the commands' touch targets, and a start sent with its key and the version shown. With
// E2E_SCREENS set, screenshots to compare with the Stitch screen.

const RULE = '-'.repeat(42);
const round = (n: number, destination: string, header: string, status: string, version: number, ...items: string[]) => ({
  id: id(n),
  storeId: STORE,
  sourceType: 'BATCH',
  sourceId: id(n + 100),
  destination,
  kind: 'ORIGINAL',
  orderId: id(n + 200),
  routing: 'ROUTED',
  suppressed: false,
  createdAt: '2026-11-22T09:30:00Z',
  lines: [destination, header, 'Ronda 1', '22/11/2026 11:30', RULE, ...items],
  kitchen: { status, version, queuedAt: '2026-11-22T09:30:00Z' },
});

const ACTIVE = [
  round(2001, 'KITCHEN', 'Mesa 04 - Pedido #4092', 'QUEUED', 0, '2 x Bife da Vazia', '   > mal passado', '1 x Polvo à Lagareiro', '   + 1 x Batata a murro'),
  round(2002, 'KITCHEN', 'Mesa 08 - Pedido #4105', 'IN_PREPARATION', 1, '1 x Costeletão Maturado'),
  round(2003, 'BAR', 'Mesa 03 - Pedido #4112', 'READY', 2, '2 x Fino 30cl'),
];
const BUMPED = [round(2004, 'COPA', 'Mesa 05 - Pedido #4098', 'BUMPED', 3, '2 x Petit Gâteau')];

const page_ = (content: object[]) => ({ content, page: 0, size: 50, totalElements: content.length });

async function atKitchen(page: Page) {
  await standInDevice(page);
  // Ana may watch the display and move its rounds here (the shared stand-in's session may not).
  await page.route('**/api/v1/auth/pos-login', (route) =>
    route.fulfill({
      json: { ...SESSION, permissions: [...SESSION.permissions, `PERM_PRODUCTION_VIEW@${STORE}`, `PERM_PRODUCTION_OPERATE@${STORE}`] },
    }),
  );
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: { storeId: STORE, areas: [] } }));
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/production-tickets/kds?*', (route) => {
    const status = new URL(route.request().url()).searchParams.getAll('status');
    return route.fulfill({ json: page_(status.includes('BUMPED') ? BUMPED : ACTIVE) });
  });
  await logIn(page);
  await page.getByRole('navigation', { name: 'Secções' }).getByRole('link', { name: 'KDS' }).click();
  await expect(page.getByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 1' })).toBeVisible();
}

async function screens(page: Page, name: string) {
  if (!process.env.E2E_SCREENS) return;
  for (const width of [1440, 1024, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 900 });
    await page.screenshot({ path: test.info().outputPath(`${name}-${width}.png`), fullPage: true });
  }
  await page.setViewportSize({ width: 1024, height: 768 });
}

test('the kitchen display is accessible, and its commands are touch-sized', async ({ page }) => {
  await atKitchen(page);

  for (const name of ['Iniciar', 'Marcar pronto', 'Retirar']) {
    const { height } = (await page.getByRole('button', { name, exact: true }).boundingBox())!;
    expect(height).toBeGreaterThanOrEqual(56);
  }
  for (const name of ['Voltar à preparação', 'Retirados', 'Todas 3', 'Cozinha 2', 'Bar 1']) {
    const { height } = (await page.getByRole('button', { name, exact: true }).boundingBox())!;
    expect(height).toBeGreaterThanOrEqual(48);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);
  await expectNoAxeViolations(page);
  await screens(page, 'kds-board');
});

test('with the KDS link too, every section label keeps to one line at 1024 × 768', async ({ page }) => {
  await atKitchen(page);

  const sections = page.getByRole('navigation', { name: 'Secções' });
  await expect(sections.getByRole('link')).toHaveCount(5);
  for (const link of await sections.getByRole('link').all()) {
    const lines = await link.evaluate((element) => {
      const text = document.createRange();
      text.selectNodeContents(element);
      return new Set([...text.getClientRects()].map((rect) => Math.round(rect.top))).size;
    });
    expect(lines, (await link.textContent()) ?? '').toBe(1);
    expect((await link.boundingBox())!.height).toBeGreaterThanOrEqual(56);
  }
});

test('the history of rounds taken off is accessible', async ({ page }) => {
  await atKitchen(page);

  await page.getByRole('button', { name: 'Retirados' }).click();

  await expect(page.getByRole('region', { name: 'Retirados' }).getByRole('button', { name: 'Repor' })).toBeVisible();
  await expectNoAxeViolations(page);
  await screens(page, 'kds-history');
});

test('"Iniciar" sends the start with an Idempotency-Key and the version shown', async ({ page }) => {
  await atKitchen(page);
  const sent: { key: string | null; body: unknown }[] = [];
  await page.route(`**/api/v1/pos/production-tickets/${id(2001)}/start`, (route) => {
    sent.push({ key: route.request().headers()['idempotency-key'] ?? null, body: route.request().postDataJSON() });
    return route.fulfill({ json: { ...ACTIVE[0], kitchen: { ...ACTIVE[0]!.kitchen, status: 'IN_PREPARATION', version: 1 } } });
  });

  await page.getByRole('article', { name: 'Mesa 04 - Pedido #4092, Ronda 1' }).getByRole('button', { name: 'Iniciar' }).click();

  await expect.poll(() => sent.length).toBe(1);
  expect(sent[0]!.body).toEqual({ version: 0 });
  expect(sent[0]!.key).toMatch(/^[0-9a-f-]{36}$/);
});
