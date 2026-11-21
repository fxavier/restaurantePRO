import { Badge, Button } from '@restaurantpro/ui';
import { TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { BOARD, type Delivery } from '../delivery';
import { CHANGED_ELSEWHERE } from '../messages';
import { DeliveryCard } from './delivery-card';

/**
 * The dispatch board (§4): one column per status the POS works on, each delivery a card. A command refused because
 * the delivery moved meanwhile is said here, not on its card: the card may have moved to another column.
 */
export function DeliveryBoard({ deliveries }: { deliveries: Delivery[] }) {
  const [changedElsewhere, setChangedElsewhere] = useState(false);
  const onChangedElsewhere = () => setChangedElsewhere(true);
  return (
    <div className="flex flex-1 flex-col gap-4">
      {changedElsewhere && (
        <div role="alert" className="flex items-center gap-3 rounded-lg border border-warning bg-warning/15 px-4 py-2">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0 text-warning" />
          <p className="flex-1 font-medium">{CHANGED_ELSEWHERE}</p>
          <Button variant="outline" size="touch" onClick={() => setChangedElsewhere(false)}>
            Fechar aviso
          </Button>
        </div>
      )}
      <div className="grid flex-1 grid-cols-1 gap-4 lg:grid-cols-3">
        {BOARD.map(({ status, title }) => {
          const inColumn = deliveries.filter((delivery) => delivery.status === status);
          const headingId = `delivery-column-${status}`;
          return (
            <section key={status} aria-labelledby={headingId} className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-3">
              <h3 id={headingId} className="flex items-center justify-between gap-2 text-xs font-semibold tracking-wide uppercase">
                {title}
                <Badge variant="secondary" className="tabular-nums">
                  {inColumn.length}
                </Badge>
              </h3>
              {inColumn.length === 0 ? (
                <p className="py-4 text-center text-sm text-muted-foreground">Nenhuma entrega.</p>
              ) : (
                <ul className="flex flex-col gap-3">
                  {inColumn.map((delivery) => (
                    <li key={delivery.orderId}>
                      <DeliveryCard delivery={delivery} onChangedElsewhere={onChangedElsewhere} />
                    </li>
                  ))}
                </ul>
              )}
            </section>
          );
        })}
      </div>
    </div>
  );
}
