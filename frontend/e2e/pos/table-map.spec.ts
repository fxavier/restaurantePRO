import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, standInDevice, STORE } from './stand-in';

// The table map in the built POS, its API stood in by page.route (the preview has no backend): what jsdom cannot
// check, the tiles' real geometry when a live change arrives, and axe on the rendered screen.

const free = (n: number, code: string) => ({
  id: id(n),
  code,
  seats: 4,
  status: 'FREE',
  locked: false,
  subtotalPrinted: false,
  reserved: false,
});
const busy = (n: number, code: string) => ({
  ...free(n, code),
  status: 'OCCUPIED',
  guests: 2,
  openedAt: '2026-11-18T10:05:00Z',
  locked: true,
  subtotalPrinted: true,
  reserved: true,
  nextReservationAt: '2026-11-18T18:30:00Z',
});
const mapOf = (...tables: object[]) => ({ storeId: STORE, areas: [{ id: id(900), code: 'SALA', name: 'Sala', tables }] });

/** M1 free and M2 with every indicator; after the change, the other way round. */
const BEFORE = mapOf(free(1, 'M1'), busy(2, 'M2'), free(3, 'M3'));
const AFTER = mapOf(busy(1, 'M1'), free(2, 'M2'), free(3, 'M3'));

async function standIn(page: Page) {
  let map: object = BEFORE;
  let release!: () => void;
  const released = new Promise<void>((resolve) => (release = resolve));
  let streams = 0;
  await standInDevice(page);
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: map }));
  await page.route('**/api/v1/pos/notifications', async (route) => {
    streams += 1;
    if (streams > 1) return; // a reconnection stays pending: the test has what it needs
    await released;
    // page.route cannot hold a stream open: one change, then the stream ends.
    await route.fulfill({ contentType: 'text/event-stream', body: 'event:change\ndata:{"topic":"TABLES"}\n\n' });
  });
  return {
    /** The backend's map changes, and the stream says so. */
    change() {
      map = AFTER;
      release();
    },
  };
}

const boxes = (page: Page) =>
  page
    .getByRole('tabpanel', { name: 'Sala' })
    .getByRole('button')
    .evaluateAll((tiles) => tiles.map((tile) => JSON.stringify(tile.getBoundingClientRect())));

test('a live change re-reads the map, and no tile moves or resizes when its state changes', async ({ page }) => {
  const backend = await standIn(page);
  await logIn(page);
  const m1 = page.getByRole('button', { name: /^Mesa M1 / });
  await expect(m1).toHaveAccessibleName('Mesa M1 Livre 4 lugares');
  await expect(page.getByRole('button', { name: /^Mesa M2 / })).toHaveAccessibleName(
    /^Mesa M2 Ocupada 2 pessoas · (\d+ h )?\d+ min Bloqueada Conta pedida Reservada \d\d:\d\d$/,
  );
  const before = await boxes(page);
  const tile = await m1.boundingBox();
  expect(tile!.width).toBeGreaterThanOrEqual(120);
  expect(tile!.height).toBeGreaterThanOrEqual(96);

  backend.change();

  await expect(m1).toHaveAccessibleName(/^Mesa M1 Ocupada /);
  await expect(page.getByRole('button', { name: /^Mesa M2 / })).toHaveAccessibleName('Mesa M2 Livre 4 lugares');
  expect(await boxes(page)).toEqual(before);
});

test('the table map is accessible', async ({ page }) => {
  await standIn(page);
  await logIn(page);
  await expect(page.getByRole('button', { name: /^Mesa M2 / })).toBeVisible();

  await expectNoAxeViolations(page);
});
