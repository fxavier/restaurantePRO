import { Button } from '@restaurantpro/ui';
import { useState } from 'react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type Delivery, intentState, isChangedElsewhere, mayRunDeliveries } from '../delivery';
import { useDeliveryTransition } from '../hooks/use-delivery-transition';
import { refusal } from '../messages';
import { DispatchDialog } from './dispatch-dialog';
import { ReturnDialog } from './return-dialog';

type Props = { delivery: Delivery; onChangedElsewhere: () => void };

/** Which of a card's dialogs is open: the courier to dispatch with, or the reason it came back. */
type Asking = 'dispatch' | 'return' | undefined;

/**
 * A card's commands (D127): a waiting or returned delivery goes out with the courier picked in a dialog; one on the
 * road is delivered, or returned with a reason. Hidden without the permission, disabled without a connection (FC §11).
 */
export function DeliveryCommands({ delivery, onChangedElsewhere }: Props) {
  const employee = useEmployee();
  const blocked = useCommandsBlocked();
  const [asking, setAsking] = useState<Asking>();
  const delivered = useDeliveryTransition(delivery.orderId, { onChangedElsewhere });
  if (!employee || !mayRunDeliveries(employee)) return null;

  const { awaitingRetry } = intentState(delivered);
  const disabled = blocked || delivered.pending;
  const close = () => setAsking(undefined);

  return (
    <div className="flex flex-col gap-2">
      <Failure error={delivered.error} />
      {awaitingRetry ? (
        <Button size="touch" className="w-full rounded-xl" disabled={blocked} onClick={delivered.retry}>
          Tentar novamente
        </Button>
      ) : delivery.status === 'OUT_FOR_DELIVERY' ? (
        <div className="grid grid-cols-2 gap-2">
          <Button
            size="touch"
            className="rounded-xl"
            disabled={disabled}
            aria-busy={delivered.pending}
            onClick={() => delivered.start({ kind: 'delivered', version: delivery.version }, undefined)}
          >
            Entregue
          </Button>
          <Button variant="outline" size="touch" className="rounded-xl bg-card" disabled={disabled} onClick={() => setAsking('return')}>
            Devolvida
          </Button>
        </div>
      ) : (
        <Button size="touch" className="w-full rounded-xl" disabled={blocked} onClick={() => setAsking('dispatch')}>
          Expedir
        </Button>
      )}
      {asking === 'dispatch' && (
        <DispatchDialog delivery={delivery} onClose={close} onChangedElsewhere={onChangedElsewhere} />
      )}
      {asking === 'return' && <ReturnDialog delivery={delivery} onClose={close} onChangedElsewhere={onChangedElsewhere} />}
    </div>
  );
}

/** A refused "Entregue"; one because the delivery moved is said by the board, as this card may have moved column. */
function Failure({ error }: { error: Error | null | undefined }) {
  if (!error || isChangedElsewhere(error)) return null;
  return (
    <p role="alert" className="text-sm font-medium text-error">
      {refusal(error, 'a entrega')}
    </p>
  );
}
