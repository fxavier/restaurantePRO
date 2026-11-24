import { StatusBadge, type StatusMap } from '@restaurantpro/ui';
import { Ban, CircleCheck, CircleDashed, CircleDot, Clock, PackageCheck, Send, Truck, type LucideIcon } from 'lucide-react';

type Tone = StatusMap<string>[string]['tone'];
const state = (label: string, icon: LucideIcon, tone: Tone) => ({ label, icon, tone });

/** The §8.3 states of the inventory documents as the API names them, each with a label and an icon (§8.4). */
export const orderStates: StatusMap<string> = {
  DRAFT: state('Rascunho', CircleDashed, 'neutral'),
  SUBMITTED: state('Submetida', Send, 'info'),
  CLOSED: state('Fechada', CircleCheck, 'success'),
  CANCELLED: state('Cancelada', Ban, 'destructive'),
};

export const documentStates: StatusMap<string> = {
  DRAFT: state('Rascunho', CircleDashed, 'neutral'),
  CONFIRMED: state('Confirmada', CircleCheck, 'success'),
  CANCELLED: state('Cancelada', Ban, 'destructive'),
};

export const countStates: StatusMap<string> = {
  DRAFT: state('Rascunho', CircleDashed, 'neutral'),
  IN_PROGRESS: state('Em contagem', CircleDot, 'info'),
  SUBMITTED: state('Submetida', Clock, 'warning'),
  APPROVED: state('Aprovada', CircleCheck, 'success'),
  REJECTED: state('Rejeitada', Ban, 'destructive'),
  CANCELLED: state('Cancelada', Ban, 'destructive'),
};

export const transferStates: StatusMap<string> = {
  DRAFT: state('Rascunho', CircleDashed, 'neutral'),
  SHIPPED: state('Em trânsito', Truck, 'info'),
  RECEIVED: state('Recebida', PackageCheck, 'success'),
  CANCELLED: state('Cancelada', Ban, 'destructive'),
};

export const versionStates: StatusMap<string> = {
  DRAFT: state('Rascunho', CircleDashed, 'neutral'),
  ACTIVE: state('Ativa', CircleCheck, 'success'),
  SUPERSEDED: state('Substituída', Clock, 'neutral'),
};

const progress: Record<string, string> = { PARTIAL: 'Parcial', FULL: 'Completa' };

/** How much of an order arrived, beside its state; nothing while none did. */
export function progressLabel(value: string | undefined): string | undefined {
  return value ? progress[value] : undefined;
}

/**
 * A document's state as the light reference's tinted pill. A state the map does not know yet is shown by its name
 * rather than hidden (never inferred, §8.3).
 */
export function StateBadge({ status, states }: { status: string | undefined; states: StatusMap<string> }) {
  const name = status ?? '—';
  return <StatusBadge look="tint" status={name} statuses={name in states ? states : { [name]: state(name, CircleDot, 'neutral') }} />;
}
