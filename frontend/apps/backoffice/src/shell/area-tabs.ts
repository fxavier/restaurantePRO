/**
 * An area's tabs in the light reference (DESIGN.md "Light reference"), as the POS area tabs and the sidebar's areas:
 * white bordered pills, the selected one solid green (the shared trigger's active fill). The list wraps rather than
 * scrolls, so every tab stays in reach at 1024 px; it sits in a white card of its own.
 */
export const AREA_TABS_LIST =
  'h-auto flex-wrap justify-start gap-2 bg-transparent p-0 group-data-[orientation=horizontal]/tabs:h-auto';

export const AREA_TAB =
  'min-h-10 flex-none gap-2 rounded-xl border-border bg-card px-4 text-foreground data-[state=inactive]:hover:bg-muted data-[state=active]:border-primary data-[state=active]:shadow-none';
