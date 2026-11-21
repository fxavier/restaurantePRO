import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

type KpiCardProps = { label: string; icon: LucideIcon; value: ReactNode; meta?: ReactNode };

/** Stitch's summary stat card: label-sm caption with an icon, the value large and tabular, a line of context below. */
export function KpiCard({ label, icon: Icon, value, meta }: KpiCardProps) {
  return (
    <section role="group" aria-label={label} className="flex flex-col gap-2 rounded-xl border bg-card p-4">
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">{label}</h3>
        <Icon aria-hidden="true" className="size-5 text-primary" />
      </div>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      {meta && <p className="text-sm text-muted-foreground">{meta}</p>}
    </section>
  );
}
