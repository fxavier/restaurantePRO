import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { logIn, standInDevice, STORE } from './stand-in';

// The section bar under the status bar in the built POS: what jsdom cannot check, its touch targets at the smallest
// POS viewport (DS: 1024 × 768, 56 px primary targets) and axe on a section's page.

test('the section bar is touch-sized, and a section page is accessible', async ({ page }) => {
  await standInDevice(page);
  await page.route('**/api/v1/pos/tables', (route) => route.fulfill({ json: { storeId: STORE, areas: [] } }));
  await page.route('**/api/v1/pos/notifications', () => undefined);
  await logIn(page);
  const sections = page.getByRole('navigation', { name: 'Secções' });
  await expect(sections.getByRole('link', { name: 'Sala & Mesas' })).toHaveAttribute('aria-current', 'page');

  for (const link of await sections.getByRole('link').all()) {
    const { height } = (await link.boundingBox())!;
    expect(height).toBeGreaterThanOrEqual(56);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(1024);

  await sections.getByRole('link', { name: 'Caixa & Turno' }).click();
  await expect(page.getByRole('heading', { name: 'Caixa & Turno' })).toBeVisible();
  await expectNoAxeViolations(page);
});
