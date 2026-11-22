import type { ReactNode } from 'react';

/**
 * A data table named by its caption, in a scroll region the keyboard can reach: the order tables are wider than the
 * detail pane and a phone, and hold nothing else focusable to scroll them by (WCAG 2.1.1, axe
 * scrollable-region-focusable). The shared Table's own scroll container is not focusable, hence this native one.
 */
export function ScrollTable({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div
      role="region"
      aria-label={`Tabela ${label}`}
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex -- a scrollable region must take focus to scroll
      tabIndex={0}
      className="relative w-full overflow-x-auto rounded-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
    >
      <table className="w-full caption-bottom text-sm">
        <caption className="sr-only">{label}</caption>
        {children}
      </table>
    </div>
  );
}
