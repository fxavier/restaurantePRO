import { expect, test } from '@playwright/test';
import { expectNoAxeViolations } from '../axe';
import { standInDevice } from './stand-in';

// The enrolment and the PIN login on their keypads, axe clean at the POS's landscape minimum and on a handheld, where
// the keys reflow instead of overlapping (WCAG 2.5.8).
for (const viewport of [
  { width: 1024, height: 768 },
  { width: 390, height: 844 },
]) {
  test(`enrolment and login at ${viewport.width}×${viewport.height} are accessible`, async ({ page }) => {
    await page.setViewportSize(viewport);
    await standInDevice(page);

    await page.goto('/enrol');
    await expect(page.getByRole('group', { name: 'Teclado do código' })).toBeVisible();
    await expectNoAxeViolations(page);
    await page.getByLabel('Código de registo').fill('K7M2P9QXABCDEFGH23456789');
    await page.getByRole('button', { name: 'Registar terminal' }).click();

    await expect(page.getByLabel('Código de empregado')).toBeVisible();
    await expectNoAxeViolations(page);
    await page.getByLabel('Código de empregado').fill('1042');
    await page.getByRole('button', { name: 'Seguinte' }).click();

    await expect(page.getByLabel('PIN')).toBeVisible();
    await expectNoAxeViolations(page);
  });
}
