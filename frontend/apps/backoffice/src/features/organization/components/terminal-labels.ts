import type { StatusMap } from '@restaurantpro/ui';
import { Ban, CircleCheck, CircleDashed, CircleHelp } from 'lucide-react';

/** `TerminalSummary.status` (REGISTERED, ACTIVE, REVOKED); an unknown one shows as itself. */
const statuses: StatusMap<string> = {
  REGISTERED: { label: 'Por ativar', icon: CircleDashed, tone: 'warning' },
  ACTIVE: { label: 'Ativo', icon: CircleCheck, tone: 'success' },
  REVOKED: { label: 'Revogado', icon: Ban, tone: 'destructive' },
};

export function terminalStatuses(status: string): StatusMap<string> {
  return status in statuses ? statuses : { [status]: { label: status, icon: CircleHelp, tone: 'neutral' } };
}

const kinds: Record<string, string> = { POS: 'Terminal POS', PRINT_BRIDGE: 'Ponte de impressão' };

/** `TerminalSummary.kind` (POS, PRINT_BRIDGE); an unknown one shows as itself. */
export function kindLabel(kind: string): string {
  return kinds[kind] ?? kind;
}
