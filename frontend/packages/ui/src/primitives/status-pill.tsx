import { cva, type VariantProps } from 'class-variance-authority';
import type { LucideIcon } from 'lucide-react';
import type { ComponentProps } from 'react';
import { cn } from '../lib/utils';
import type { StatusTone } from './status-badge';

/**
 * The one source of a state's tint (DESIGN.md "Light reference"). Every tint pairs with its own AA text (≥ 6.7:1);
 * neutral is the muted surface with the secondary text (6.0:1). `primary` reads as success, the reference's green.
 */

/** A tinted surface with its AA text: a pill, a chip, a counter. */
export const TONE_TINT: Record<StatusTone, string> = {
  neutral: 'bg-muted text-muted-foreground',
  primary: 'bg-success-tint text-success-text',
  success: 'bg-success-tint text-success-text',
  warning: 'bg-warning-tint text-warning-text',
  destructive: 'bg-destructive-tint text-destructive-text',
  info: 'bg-info-tint text-info-text',
};

/**
 * A card or tile in a state: its tint and border (decorative: the state is also said in words). Text on it keeps
 * `foreground` and `muted-foreground`, which pass AA on every tint, unless {@link TONE_INK} is added.
 */
export const TONE_CARD: Record<StatusTone, string> = {
  neutral: 'border-border bg-muted',
  primary: 'border-success-border bg-success-tint',
  success: 'border-success-border bg-success-tint',
  warning: 'border-warning-border bg-warning-tint',
  destructive: 'border-destructive-border bg-destructive-tint',
  info: 'border-info-border bg-info-tint',
};

/** The state's AA text alone, on its tint or on white (≥ 6.7:1 on both). */
export const TONE_INK: Record<StatusTone, string> = {
  neutral: 'text-muted-foreground',
  primary: 'text-success-text',
  success: 'text-success-text',
  warning: 'text-warning-text',
  destructive: 'text-destructive-text',
  info: 'text-info-text',
};

export const statusPillVariants = cva('items-center text-sm font-medium [&>svg]:size-4 [&>svg]:shrink-0', {
  variants: {
    tone: TONE_TINT,
    size: {
      /** A state beside a name or a heading. */
      pill: 'inline-flex w-fit shrink-0 gap-1 rounded-full px-2.5 py-0.5 whitespace-nowrap',
      /** A status bar or footer chip, as tall as the reference's ("Ligado", "1 pagamento por confirmar"). */
      chip: 'flex gap-2 rounded-xl px-3 py-2',
    },
  },
  defaultVariants: { size: 'pill' },
});

type StatusPillProps = ComponentProps<'span'> &
  VariantProps<typeof statusPillVariants> & {
    tone: StatusTone;
    /** Decorative: hidden from assistive technology, the words say the state. */
    icon?: LucideIcon;
  };

/** A state as a tinted pill: its icon and its word, never colour alone (§8.4, DS principle 3). */
export function StatusPill({ tone, size, icon: Icon, className, children, ...props }: StatusPillProps) {
  return (
    <span data-slot="status-pill" className={cn(statusPillVariants({ tone, size }), className)} {...props}>
      {Icon && <Icon aria-hidden="true" />}
      {children}
    </span>
  );
}
