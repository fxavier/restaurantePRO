import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';

test('the BackOffice boots to its placeholder, in Portuguese and accessible', async ({ page }) => {
  await page.goto('/');

  await expect(page.getByRole('heading', { level: 1, name: 'restaurantPRO — BackOffice' })).toBeVisible();
  await expect(page.locator('html')).toHaveAttribute('lang', 'pt');
  await expectNoAxeViolations(page);
});
