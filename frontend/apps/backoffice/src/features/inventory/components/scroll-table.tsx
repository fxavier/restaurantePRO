import type { ReactNode } from 'react';

/**
 * A data table named by its caption, inside a scroll region a keyboard can reach (WCAG 2.1.1: the inventory tables
 * are wider than a phone and hold no other focusable element to scroll them by). The shared Table's own scroll
 * container is not focusable, hence this native one.
 */
export function ScrollTable({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div
      role="region"
      aria-label={label}
      // A scrollable region must take the keyboard focus to be scrolled without a mouse (axe scrollable-region-focusable).
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      className="relative w-full overflow-x-auto rounded-xl focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <table className="w-full caption-bottom text-sm">
        <caption className="sr-only">{label}</caption>
        {children}
      </table>
    </div>
  );
}
