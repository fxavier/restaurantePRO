import { cn } from '@restaurantpro/ui';
import type { ReactNode } from 'react';

/**
 * A body that scrolls on its own (WCAG 2.1.1, axe `scrollable-region-focusable`): named, and in the tab order, so a
 * keyboard user can reach it and scroll it with the arrow keys even when nothing inside takes the focus.
 */
export function ScrollRegion({ label, className, children }: { label: string; className?: string; children: ReactNode }) {
  return (
    <div
      role="region"
      aria-label={label}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a scrolling region must take the focus (WCAG 2.1.1)
      tabIndex={0}
      className={cn('overflow-y-auto outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset', className)}
    >
      {children}
    </div>
  );
}
