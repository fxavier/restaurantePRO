import type { StatusMap } from '@restaurantpro/ui';
import { CheckCircle2, CircleDashed, Clock, HelpCircle, Lock, LockOpen, XCircle, type LucideIcon } from 'lucide-react';
import type { ClosingStatus, ReconciliationLine } from './types';

type Status = { label: string; icon: LucideIcon; tone: StatusMap<string>[string]['tone'] };

const CLOSING: Record<string, Status> = {
  DRAFT: { label: 'Rascunho', icon: CircleDashed, tone: 'neutral' },
  SUBMITTED: { label: 'Submetido', icon: Clock, tone: 'warning' },
  APPROVED: { label: 'Aprovado', icon: CheckCircle2, tone: 'success' },
  REJECTED: { label: 'Rejeitado', icon: XCircle, tone: 'destructive' },
};

const DAY: Record<string, Status> = {
  OPEN: { label: 'Aberto', icon: LockOpen, tone: 'info' },
  OPERATIONALLY_CLOSED: { label: 'Fechado operacionalmente', icon: Clock, tone: 'warning' },
  FINANCIALLY_CLOSED: { label: 'Fechado financeiramente', icon: Lock, tone: 'success' },
};

/** The states of §8.3 as label, icon and tone; a state the screen does not know is shown as the server named it. */
function statuses(known: Record<string, Status>, status: string | undefined): StatusMap<string> {
  const key = status ?? '';
  return { [key]: known[key] ?? { label: status ?? '—', icon: HelpCircle, tone: 'neutral' } };
}

export const closingStatuses = (status: string | undefined) => statuses(CLOSING, status);
export const dayStatuses = (status: string | undefined) => statuses(DAY, status);

export const CLOSING_FILTERS: { value: ClosingStatus | ''; label: string }[] = [
  { value: 'SUBMITTED', label: 'Por aprovar' },
  { value: 'APPROVED', label: 'Aprovados' },
  { value: 'REJECTED', label: 'Rejeitados' },
  { value: '', label: 'Todos' },
];

export type CashDay = { id: string; businessDate: string; status: string };

/**
 * The cash days of the period, once each, newest first: the reconciliation repeats a day per register and method,
 * and it is the only BackOffice read that names the days (no day list in the contract).
 */
export function cashDays(lines: readonly ReconciliationLine[]): CashDay[] {
  const days = new Map<string, CashDay>();
  for (const line of lines) {
    if (line.cashDayId && !days.has(line.cashDayId)) {
      days.set(line.cashDayId, { id: line.cashDayId, businessDate: line.businessDate ?? '', status: line.dayStatus ?? '' });
    }
  }
  return [...days.values()].sort((a, b) => b.businessDate.localeCompare(a.businessDate));
}
