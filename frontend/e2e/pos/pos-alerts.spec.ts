import { expect, type Page, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { id, logIn, SESSION, standInDevice, STORE } from './stand-in';

// The light reference's status bar and footer on /tables, in the built POS with its API stood in (the preview has no
// backend): store · terminal · operator, the pending prints, the payments in doubt, the late kitchen rounds and the
// keyboard hints, at the POS's two sizes, with axe. POS_SCREENSHOTS=<dir> also saves the screen at each size there.

const ORDER = id(777);
const PERMISSIONS = ['DINING_VIEW', 'ORDER_EDIT', 'PRODUCTION_VIEW', 'PAYMENT_COLLECT', 'RESERVATION_MANAGE'].map(
  (permission) => `PERM_${permission}@${STORE}`,
);

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
const occupied = { status: 'OCCUPIED', guests: 4, openedAt: new Date(Date.now() - 32 * 60_000).toISOString() };
const MAP = {
  storeId: STORE,
  areas: [
    {
      id: id(900),
      code: 'SALA',
      name: 'Sala',
      tables: [
        table(1, '01'),
        table(4, '04', { ...occupied, openOrderId: id(504) }),
        table(5, '05', { ...occupied, guests: 2, openOrderId: id(505) }),
        table(6, '06', { ...occupied, subtotalPrinted: true, openOrderId: id(506) }),
        table(7, '07', { locked: true }),
        table(8, '08'),
        table(9, '09', { reserved: true, nextReservationAt: new Date(Date.now() + 3 * 3600_000).toISOString() }),
        table(10, '10'),
      ],
    },
    { id: id(901), code: 'ESPLANADA', name: 'Esplanada', tables: [table(20, 'E1')] },
  ],
};

async function standIn(page: Page) {
  await standInDevice(page);
  await page.route('**/api/v1/auth/pos-login', (route) =>
    route.fulfill({ json: { ...SESSION, permissions: PERMISSIONS } }),
  );
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: MAP }));
  // The change stream stays pending: the status bar says "A ligar…", the screen is read as it is.
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await page.route('**/api/v1/pos/production/alerts', (route) =>
    route.fulfill({ json: { pendingPrintJobs: 1, delayedKitchenRounds: 2, kitchenDelayMinutes: 15 } }),
  );
  await page.route('**/api/v1/pos/settlement/alerts', (route) =>
    route.fulfill({ json: { paymentsToConfirm: 1, oldestOrderId: ORDER } }),
  );
}

for (const viewport of [
  { width: 1024, height: 768 },
  { width: 1440, height: 900 },
]) {
  test(`the status bar and the footer show the store's alerts at ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await standIn(page);
    await logIn(page);

    const bar = page.getByRole('banner');
    await expect(bar).toContainText('Loja Baixa');
    await expect(bar).toContainText('POS-02');
    await expect(bar).toContainText('Ana Silva');
    await expect(page.getByRole('status', { name: 'Impressões' })).toHaveText('1 impressão pendente');
    const alerts = page.getByRole('status', { name: 'Alertas' });
    await expect(alerts.getByRole('link', { name: '1 pagamento por confirmar' })).toHaveAttribute(
      'href',
      `/orders/${ORDER}/payment`,
    );
    await expect(alerts.getByRole('link', { name: 'Cozinha: 2 rondas > 15 min' })).toHaveAttribute('href', '/kds');
    await expect(page.getByRole('note', { name: 'Atalhos de teclado' })).toHaveText(
      'F2 reserva · F3 takeaway · / procurar mesa',
    );
    // Every chip is on screen, none pushed off by the map.
    for (const chip of [alerts.getByRole('link').first(), alerts.getByRole('link').last()]) {
      await expect(chip).toBeInViewport();
    }

    await expectNoAxeViolations(page);
    if (process.env.POS_SCREENSHOTS) {
      await page.screenshot({ path: `${process.env.POS_SCREENSHOTS}/tables-${viewport.width}x${viewport.height}.png` });
    }
  });
}

test('"/" goes to the table search, and F3 opens a new takeaway, from the keyboard', async ({ page }) => {
  await standIn(page);
  await logIn(page);
  await expect(page.getByRole('button', { name: /^Mesa 01 / })).toBeVisible();

  await page.keyboard.press('/');
  await expect(page.getByRole('searchbox', { name: 'Procurar mesa' })).toBeFocused();
  await page.keyboard.type('05');
  await expect(page.getByRole('button', { name: /^Mesa / })).toHaveCount(1);

  await page.getByRole('searchbox', { name: 'Procurar mesa' }).blur();
  await page.keyboard.press('F3');
  await expect(page.getByRole('dialog', { name: 'Novo pedido fora de sala' })).toBeVisible();
});
