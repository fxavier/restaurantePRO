import { cn } from '@restaurantpro/ui';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

type KpiCardProps = {
  label: string;
  icon: LucideIcon;
  value: ReactNode;
  meta?: ReactNode;
  /** The box's surface: `bg-muted`, or a state's `TONE_TINT` (its AA text with it). */
  tint: string;
};

/**
 * A summary figure as the POS payment summary's: a tinted box (inside a white card), its caption with an icon, the
 * value large and tabular, a line of context below. The words name the figure; the tint only groups it.
 */
export function KpiCard({ label, icon: Icon, value, meta, tint }: KpiCardProps) {
  return (
    <section role="group" aria-label={label} className={cn('flex flex-col gap-2 rounded-xl px-4 py-3', tint)}>
      <div className="flex items-start justify-between gap-2">
        <h3 className="text-xs font-semibold tracking-wide uppercase">{label}</h3>
        <Icon aria-hidden="true" className="size-5" />
      </div>
      <p className="text-2xl font-semibold tabular-nums">{value}</p>
      {meta && <p className="text-sm">{meta}</p>}
    </section>
  );
}
