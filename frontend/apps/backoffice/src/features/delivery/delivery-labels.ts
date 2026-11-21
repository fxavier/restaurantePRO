import type { StatusMap } from '@restaurantpro/ui';
import { CircleCheck, CircleDashed, CircleX, Truck, Undo2 } from 'lucide-react';
import type { DeliveryStatus } from './types';

/** The delivery lifecycle (§8.3, D127): PENDING → OUT_FOR_DELIVERY → DELIVERED, or RETURNED, or CANCELLED. */
export const deliveryStatuses: StatusMap<DeliveryStatus> = {
  PENDING: { label: 'Pendente', icon: CircleDashed, tone: 'neutral' },
  OUT_FOR_DELIVERY: { label: 'Em rota', icon: Truck, tone: 'info' },
  DELIVERED: { label: 'Entregue', icon: CircleCheck, tone: 'success' },
  RETURNED: { label: 'Devolvida', icon: Undo2, tone: 'warning' },
  CANCELLED: { label: 'Cancelada', icon: CircleX, tone: 'destructive' },
};
