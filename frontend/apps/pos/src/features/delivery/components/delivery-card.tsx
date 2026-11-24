import { cn, type StatusTone, TONE_CARD } from '@restaurantpro/ui';
import { Clock, MapPin, Phone, ReceiptText, Undo2 } from 'lucide-react';
import { Link } from 'react-router';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { addressText, type Delivery, type DeliveryStatus, storeClock } from '../delivery';
import { DeliveryCommands } from './delivery-commands';

/**
 * Each status's tint (DESIGN.md "Light reference"), as the table map's: waiting to go out amber, on the road blue, back
 * at the store red. The column's title says the status in words.
 */
export const STATUS_TONE: Record<DeliveryStatus, StatusTone> = {
  PENDING: 'warning',
  OUT_FOR_DELIVERY: 'info',
  RETURNED: 'destructive',
  DELIVERED: 'success',
  CANCELLED: 'neutral',
};

/** One delivery on the board: its order, zone, address, phone, promise and, once back, why; then its commands. */
export function DeliveryCard({ delivery, onChangedElsewhere }: { delivery: Delivery; onChangedElsewhere: () => void }) {
  const timeZone = useStoreTimeZone();
  const title = `Entrega n.º ${delivery.orderNumber}`;
  return (
    <article
      aria-label={title}
      className={cn('flex flex-col gap-1.5 rounded-xl border-2 p-3', TONE_CARD[STATUS_TONE[delivery.status]])}
    >
      <header className="flex items-center justify-between gap-2">
        <h4 className="text-2xl font-bold tabular-nums">n.º {delivery.orderNumber}</h4>
        <span className="rounded-full bg-card px-2.5 py-0.5 text-sm font-semibold">{delivery.zoneCode}</span>
      </header>
      <p className="flex items-start gap-2">
        <MapPin aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        {addressText(delivery.address)}
      </p>
      {delivery.phone && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground tabular-nums">
          <Phone aria-hidden="true" className="size-4 shrink-0" />
          {delivery.phone}
        </p>
      )}
      {delivery.promisedAt && (
        <p className="flex items-center gap-2 text-sm font-medium tabular-nums">
          <Clock aria-hidden="true" className="size-4 shrink-0" />
          Prometido às {storeClock(delivery.promisedAt, timeZone)}
        </p>
      )}
      {delivery.returnReason && (
        <p className="flex items-center gap-2 text-sm font-medium text-destructive-text">
          <Undo2 aria-hidden="true" className="size-4 shrink-0" />
          Devolvida: {delivery.returnReason}
        </p>
      )}
      <Link
        to={`/orders/${delivery.orderId}`}
        className="flex h-12 items-center gap-2 self-start rounded-md px-2 font-semibold text-success-text underline-offset-4 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ReceiptText aria-hidden="true" className="size-5" />
        Abrir pedido
      </Link>
      <DeliveryCommands delivery={delivery} onChangedElsewhere={onChangedElsewhere} />
    </article>
  );
}
