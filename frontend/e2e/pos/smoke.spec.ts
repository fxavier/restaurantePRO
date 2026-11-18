import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

test('the POS boots to its placeholder, in Portuguese and accessible', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { level: 1, name: 'restaurantPRO — Ponto de venda' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'pt');
  await expectNoAxeViolations(page);
});
