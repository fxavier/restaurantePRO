import { Badge } from '@restaurantpro/ui';
import { Clock, MapPin, Phone, ReceiptText, Undo2 } from 'lucide-react';
import { Link } from 'react-router';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { addressText, type Delivery, storeClock } from '../delivery';
import { DeliveryCommands } from './delivery-commands';

/** One delivery on the board: its order, zone, address, phone, promise and, once back, why; then its commands. */
export function DeliveryCard({ delivery, onChangedElsewhere }: { delivery: Delivery; onChangedElsewhere: () => void }) {
  const timeZone = useStoreTimeZone();
  const title = `Entrega n.º ${delivery.orderNumber}`;
  return (
    <article aria-label={title} className="flex flex-col gap-2 rounded-lg border bg-muted p-3">
      <header className="flex items-center justify-between gap-2">
        <h4 className="text-xl font-semibold tabular-nums">n.º {delivery.orderNumber}</h4>
        <Badge variant="outline">{delivery.zoneCode}</Badge>
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
          <Clock aria-hidden="true" className="size-4 shrink-0 text-warning" />
          Prometido às {storeClock(delivery.promisedAt, timeZone)}
        </p>
      )}
      {delivery.returnReason && (
        <p className="flex items-center gap-2 text-sm font-medium text-error">
          <Undo2 aria-hidden="true" className="size-4 shrink-0" />
          Devolvida: {delivery.returnReason}
        </p>
      )}
      <Link
        to={`/orders/${delivery.orderId}`}
        className="flex h-12 items-center gap-2 self-start rounded-md px-2 font-semibold text-primary-hover underline-offset-4 outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
      >
        <ReceiptText aria-hidden="true" className="size-5" />
        Abrir pedido
      </Link>
      <DeliveryCommands delivery={delivery} onChangedElsewhere={onChangedElsewhere} />
    </article>
  );
}
