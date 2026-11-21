import { Button } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type Delivery, intentState, isChangedElsewhere, mayRunDeliveries } from '../delivery';
import { useDeliveryTransition } from '../hooks/use-delivery-transition';
import { refusal } from '../messages';
import { ReturnDialog } from './return-dialog';

type Props = { delivery: Delivery; onChangedElsewhere: () => void };

/**
 * A card's commands (D127): a waiting or returned delivery goes out with the logged-in employee as its courier; one on
 * the road is delivered or returned. Hidden without the permission, disabled without a connection (FC §11).
 */
export function DeliveryCommands({ delivery, onChangedElsewhere }: Props) {
  const employee = useEmployee();
  const blocked = useCommandsBlocked();
  const courierId = useId();
  const [returning, setReturning] = useState(false);
  const transition = useDeliveryTransition(delivery.orderId, { onChangedElsewhere });
  if (!employee || !mayRunDeliveries(employee)) return null;

  const { awaitingRetry } = intentState(transition);
  const disabled = blocked || transition.pending;
  const { version } = delivery;
  // A refusal because the delivery moved is said by the board: this card may have moved to another column.
  const failure = isChangedElsewhere(transition.error) ? undefined : transition.error;

  return (
    <div className="flex flex-col gap-2">
      {failure && (
        <p role="alert" className="text-sm font-medium text-error">
          {refusal(failure, WHAT[transition.last?.request.kind ?? 'dispatch'])}
        </p>
      )}
      {awaitingRetry ? (
        <Button size="touch" className="w-full" disabled={blocked} onClick={transition.retry}>
          Tentar novamente
        </Button>
      ) : delivery.status === 'OUT_FOR_DELIVERY' ? (
        <div className="grid grid-cols-2 gap-2">
          <Button
            size="touch"
            disabled={disabled}
            aria-busy={transition.pending}
            onClick={() => transition.start({ kind: 'delivered', version }, undefined)}
          >
            Entregue
          </Button>
          <Button variant="outline" size="touch" disabled={disabled} onClick={() => setReturning(true)}>
            Devolvida
          </Button>
        </div>
      ) : (
        <>
          <Button
            size="touch"
            className="w-full"
            disabled={disabled}
            aria-busy={transition.pending}
            aria-describedby={courierId}
            onClick={() => transition.start({ kind: 'dispatch', courierEmployeeId: employee.employeeId, version }, undefined)}
          >
            Expedir
          </Button>
          <p id={courierId} className="text-sm text-muted-foreground">
            Estafeta: {employee.name}
          </p>
        </>
      )}
      {returning && (
        <ReturnDialog delivery={delivery} onClose={() => setReturning(false)} onChangedElsewhere={onChangedElsewhere} />
      )}
    </div>
  );
}

const WHAT = { dispatch: 'a expedição', delivered: 'a entrega', returned: 'a devolução' } as const;
