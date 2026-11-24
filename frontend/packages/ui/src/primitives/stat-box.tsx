import type { LucideIcon } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { cn } from '../lib/utils';
import type { StatusTone } from './status-badge';
import { TONE_TINT } from './status-pill';

type StatBoxProps = {
  /** A state's tint with its AA text (`TONE_TINT`), or `muted`: the raised surface, the text as around it. */
  tone: StatusTone | 'muted';
  /** Names the figure: the box's accessible name. */
  caption: string;
  /** Decorative: the caption says what the figure is. */
  icon: LucideIcon;
  /** The figure, large and tabular; a caller may put its own loading or error state here. */
  value: ReactNode;
  /** A line of context below the figure. */
  meta?: ReactNode;
  /** The caption as a heading of this level, where the figures are sections of the page; plain text by default. */
  captionAs?: 'h2' | 'h3' | 'h4';
  className?: string;
};

/**
 * A summary figure (DESIGN.md "Light reference", KPI box): a tinted box inside a white card, its caption with an icon,
 * the value large and tabular. The words name the figure; the tint only groups it, never alone.
 */
export function StatBox({ tone, caption, icon: Icon, value, meta, captionAs: Caption = 'span', className }: StatBoxProps) {
  const id = useId();
  return (
    <div
      role="group"
      aria-labelledby={id}
      data-slot="stat-box"
      className={cn('flex flex-col gap-2 rounded-xl px-4 py-3', tone === 'muted' ? 'bg-muted' : TONE_TINT[tone], className)}
    >
      <div className="flex items-start justify-between gap-2">
        <Caption id={id} className="text-xs font-semibold tracking-wide uppercase">
          {caption}
        </Caption>
        <Icon aria-hidden="true" className="size-5 shrink-0" />
      </div>
      <div className="text-2xl font-semibold tabular-nums">{value}</div>
      {meta && <p className="text-sm">{meta}</p>}
    </div>
  );
}
