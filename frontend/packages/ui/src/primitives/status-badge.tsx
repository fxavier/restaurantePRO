import type { LucideIcon } from 'lucide-react';
import { Badge } from '../components/badge';

export type StatusTone = 'neutral' | 'primary' | 'success' | 'warning' | 'destructive';

export type StatusMap<S extends string> = Record<S, { label: string; icon: LucideIcon; tone: StatusTone }>;

const variantOf = {
  neutral: 'secondary',
  primary: 'default',
  success: 'success',
  warning: 'warning',
  destructive: 'destructive',
} as const;

/** A status by colour, icon and label together, never colour alone (§8.4, DS principle 3). */
export function StatusBadge<S extends string>({ status, statuses }: { status: S; statuses: StatusMap<S> }) {
  const { label, icon: Icon, tone } = statuses[status];
  return (
    <Badge variant={variantOf[tone]}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  );
}
