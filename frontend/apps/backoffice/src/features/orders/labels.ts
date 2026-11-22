import type { StatusMap } from '@restaurantpro/ui';
import { CircleCheck, CircleDashed, CircleX, Send } from 'lucide-react';
import type { BatchView, LineView, OrderLifecycle, OrderMode } from './types';

/** The order lifecycle (§8.3): OPEN → CLOSED, or CANCELLED; exactly as the server returns it. */
export const lifecycles: StatusMap<OrderLifecycle> = {
  OPEN: { label: 'Aberto', icon: CircleDashed, tone: 'info' },
  CLOSED: { label: 'Fechado', icon: CircleCheck, tone: 'success' },
  CANCELLED: { label: 'Cancelado', icon: CircleX, tone: 'destructive' },
};

export const modes: Record<OrderMode, string> = {
  DINE_IN: 'Mesa',
  COUNTER: 'Balcão',
  TAKEAWAY: 'Take-away',
  DELIVERY: 'Entrega',
};

/** A line is a draft until its round is confirmed ("Pedir"), then it belongs to that round. */
export const lineStatuses: StatusMap<LineView['status']> = {
  DRAFT: { label: 'Rascunho', icon: CircleDashed, tone: 'neutral' },
  CONFIRMED: { label: 'Confirmada', icon: CircleCheck, tone: 'success' },
};

/** A round once confirmed, then sent to production. */
export const roundStatuses: StatusMap<BatchView['status']> = {
  CONFIRMED: { label: 'Confirmada', icon: CircleCheck, tone: 'neutral' },
  DISPATCHED: { label: 'Enviada', icon: Send, tone: 'success' },
};

const adjustmentKinds: Record<string, string> = {
  DISCOUNT: 'Desconto',
  OFFER: 'Oferta',
  REWARD: 'Recompensa',
  REVERSAL: 'Anulação',
};

/** The kind of a price adjustment; a kind this screen does not know yet shows as the server names it. */
export function adjustmentKind(kind: string): string {
  return adjustmentKinds[kind] ?? kind;
}
