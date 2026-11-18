import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';

/**
 * WCAG 2.2 AA automated checks on the page as it is now (FC §13), once its finite animations and transitions have
 * ended: a dialog fading in, or a button turning enabled, would otherwise be measured half-transparent (a contrast
 * failure no user sees). Endless ones (a spinner) are left running.
 */
export async function expectNoAxeViolations(page: Page) {
  await page.waitForFunction(() =>
    document
      .getAnimations()
      .every((animation) => animation.playState !== 'running' || animation.effect?.getTiming().iterations === Infinity),
  );
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  expect(violations.map(({ id, impact, nodes }) => ({ id, impact, targets: nodes.map((n) => n.target) }))).toEqual([]);
}
