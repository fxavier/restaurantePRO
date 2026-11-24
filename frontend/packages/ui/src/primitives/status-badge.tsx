import type { LucideIcon } from 'lucide-react';
import { Badge } from '../components/badge';
import { StatusPill } from './status-pill';

export type StatusTone = 'neutral' | 'primary' | 'success' | 'warning' | 'destructive' | 'info';

export type StatusMap<S extends string> = Record<S, { label: string; icon: LucideIcon; tone: StatusTone }>;

const variantOf = {
  neutral: 'secondary',
  primary: 'default',
  success: 'success',
  warning: 'warning',
  destructive: 'destructive',
  info: 'info',
} as const;

/**
 * A status by colour, icon and label together, never colour alone (§8.4, DS principle 3). `look="tint"` is the light
 * reference's tinted pill ({@link StatusPill}); `solid` the filled badge.
 */
export function StatusBadge<S extends string>({
  status,
  statuses,
  look = 'solid',
}: {
  status: S;
  statuses: StatusMap<S>;
  look?: 'solid' | 'tint';
}) {
  const { label, icon: Icon, tone } = statuses[status];
  if (look === 'tint') {
    return (
      <StatusPill tone={tone} icon={Icon}>
        {label}
      </StatusPill>
    );
  }
  return (
    <Badge variant={variantOf[tone]}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}
