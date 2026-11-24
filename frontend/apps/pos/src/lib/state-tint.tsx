import { cn, type StatusMap, type StatusTone } from '@restaurantpro/ui';
import type { LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';

/**
 * DESIGN.md "Light reference": a state's tint with its AA text colour (≥ 6.7:1), as the table map's tiles and footer
 * chips use them. Neutral is the muted surface with the secondary text (6.0:1).
 */
export const TONE_TEXT: Record<StatusTone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-success-tint text-success-text',
  success: 'bg-success-tint text-success-text',
  warning: 'bg-warning-tint text-warning-text',
  destructive: 'bg-destructive-tint text-destructive-text',
  info: 'bg-info-tint text-info-text',
};

/**
 * A card in a state, like a table tile: its tint and its border (decorative: the state is also said in words). The
 * text on it keeps `foreground` and `muted-foreground`, which pass AA on every tint.
 */
export const TONE_CARD: Record<StatusTone, string> = {
  neutral: 'border-border bg-muted',
  primary: 'border-success-border bg-success-tint',
  success: 'border-success-border bg-success-tint',
  warning: 'border-warning-border bg-warning-tint',
  destructive: 'border-destructive-border bg-destructive-tint',
  info: 'border-info-border bg-info-tint',
};

type ChipProps = { tone: StatusTone; icon: LucideIcon; children: ReactNode; className?: string };

/** A state as a tinted pill: its icon and its word, never colour alone (§8.4). */
export function StateChip({ tone, icon: Icon, children, className }: ChipProps) {
  return (
    <span
      className={cn(
        'inline-flex w-fit shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-sm font-medium whitespace-nowrap',
        TONE_TEXT[tone],
        className,
      )}
    >
      <Icon aria-hidden="true" className="size-4 shrink-0" />
      {children}
    </span>
  );
}

/** {@link StateChip} for a status of a {@link StatusMap}, the light POS's counterpart of the shared `StatusBadge`. */
export function StatusChip<S extends string>({ status, statuses }: { status: S; statuses: StatusMap<S> }) {
  const { label, icon, tone } = statuses[status];
  return (
    <StateChip tone={tone} icon={icon}>
      {label}
    </StateChip>
  );
}
