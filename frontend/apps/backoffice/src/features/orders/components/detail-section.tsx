import type { ReactNode } from 'react';

type DetailSectionProps = { title: string; empty: string | undefined; children: ReactNode };

/** One part of the open order under its title; `empty` says what is missing instead of an empty table. */
export function DetailSection({ title, empty, children }: DetailSectionProps) {
  return (
    <div className="flex flex-col gap-2">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{title}</h3>
      {empty ? <p className="text-sm text-muted-foreground">{empty}</p> : children}
    </div>
  );
}
