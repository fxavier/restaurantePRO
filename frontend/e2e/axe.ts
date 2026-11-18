import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/** WCAG 2.2 AA automated checks on the page as it is now, once its animations have ended (FC §13). */
export async function expectNoAxeViolations(page: Page) {
  // A dialog fading in is measured at its final colours, not half transparent.
  await page.waitForFunction(() => document.getAnimations().every((animation) => animation.playState !== 'running'));
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(violations.map(({ id, impact, nodes }) => ({ id, impact, targets: nodes.map((n) => n.target) }))).toEqual([]);
}
