import type { Page } from '@playwright/test';

/** The POS viewports the light reference is checked at: the smallest POS screen, and a desktop. */
export const SCREEN_SIZES = [
  { width: 1024, height: 768 },
  { width: 1440, height: 900 },
] as const;

/**
 * Only with E2E_SCREENS (a folder): the page as it is now at each of {@link SCREEN_SIZES}, saved as
 * `<name>-<width>x<height>.png`, then back at 1024 × 768. A dialog is taken as the viewport shows it.
 */
export async function saveScreens(page: Page, name: string, { fullPage = true }: { fullPage?: boolean } = {}) {
  const dir = process.env.E2E_SCREENS;
  if (!dir) return;
  for (const { width, height } of SCREEN_SIZES) {
    await page.setViewportSize({ width, height });
    await page.screenshot({ path: `${dir}/${name}-${width}x${height}.png`, animations: 'disabled', fullPage });
  }
  await page.setViewportSize(SCREEN_SIZES[0]);
}
