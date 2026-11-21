import { Button } from '@restaurantpro/ui';
import { useId, useState } from 'react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type Delivery, intentState, isChangedElsewhere, mayRunDeliveries, type Transition } from '../delivery';
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
  const [returning, setReturning] = useState(false);
  const transition = useDeliveryTransition(delivery.orderId, { onChangedElsewhere });
  if (!employee || !mayRunDeliveries(employee)) return null;

  const { awaitingRetry } = intentState(transition);
  const disabled = blocked || transition.pending;
  const { version } = delivery;
  const start = (request: Transition) => transition.start(request, undefined);

  return (
    <div className="flex flex-col gap-2">
      <Failure error={transition.error} kind={transition.last?.request.kind} />
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
            onClick={() => start({ kind: 'delivered', version })}
          >
            Entregue
          </Button>
          <Button variant="outline" size="touch" disabled={disabled} onClick={() => setReturning(true)}>
            Devolvida
          </Button>
        </div>
      ) : (
        <Dispatch
          courier={employee}
          disabled={disabled}
          pending={transition.pending}
          onDispatch={() => start({ kind: 'dispatch', courierEmployeeId: employee.employeeId, version })}
        />
      )}
      {returning && (
        <ReturnDialog delivery={delivery} onClose={() => setReturning(false)} onChangedElsewhere={onChangedElsewhere} />
      )}
    </div>
  );
}

/** A refusal; one because the delivery moved is said by the board, as this card may have moved to another column. */
function Failure({ error, kind }: { error: Error | null | undefined; kind: Transition['kind'] | undefined }) {
  if (!error || isChangedElsewhere(error)) return null;
  return (
    <p role="alert" className="text-sm font-medium text-error">
      {refusal(error, WHAT[kind ?? 'dispatch'])}
    </p>
  );
}

type DispatchProps = { courier: { name: string }; disabled: boolean; pending: boolean; onDispatch: () => void };

/** "Expedir", with the logged-in employee named as the courier. */
function Dispatch({ courier, disabled, pending, onDispatch }: DispatchProps) {
  const courierId = useId();
  return (
    <>
      <Button
        size="touch"
        className="w-full"
        disabled={disabled}
        aria-busy={pending}
        aria-describedby={courierId}
        onClick={onDispatch}
      >
        Expedir
      </Button>
      <p id={courierId} className="text-sm text-muted-foreground">
        Estafeta: {courier.name}
      </p>
    </>
  );
}

const WHAT = { dispatch: 'a expedição', delivered: 'a entrega', returned: 'a devolução' } as const;
