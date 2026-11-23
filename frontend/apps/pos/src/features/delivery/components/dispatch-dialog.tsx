import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Skeleton,
} from '@restaurantpro/ui';
import { Bike } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type Delivery, intentState } from '../delivery';
import { useCouriers } from '../hooks/use-couriers';
import { useDeliveryTransition } from '../hooks/use-delivery-transition';
import { loadFailure } from '../messages';
import { ChoiceGroup } from './choice-group';
import { CommandMessage } from './command-message';

type Props = { delivery: Delivery; onClose: () => void; onChangedElsewhere: () => void };

/**
 * A delivery taken out (PENDING or RETURNED → OUT_FOR_DELIVERY) by the courier the operator picks (D127). The
 * logged-in employee comes picked when they are one of the store's couriers: the driver dispatching their own trip.
 */
export function DispatchDialog({ delivery, onClose, onChangedElsewhere }: Props) {
  const [picked, setPicked] = useState<string>();
  const couriers = useCouriers();
  const self = useEmployee()?.employeeId;
  const courierId = picked ?? couriers.data?.find(({ id }) => id === self)?.id;
  const blocked = useCommandsBlocked();
  const dispatch = useDeliveryTransition(delivery.orderId, {
    onDone: onClose,
    onChangedElsewhere() {
      onChangedElsewhere();
      onClose();
    },
  });
  const { awaitingRetry, locked } = intentState(dispatch);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !courierId) return;
    if (awaitingRetry) dispatch.retry();
    else dispatch.start({ kind: 'dispatch', courierEmployeeId: courierId, version: delivery.version }, undefined);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !locked && onClose()}>
      <DialogContent showCloseButton={false} className="sm:max-w-md">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Expedir a entrega n.º {delivery.orderNumber}</DialogTitle>
            <DialogDescription>Escolha o estafeta que leva a entrega.</DialogDescription>
          </DialogHeader>
          <Couriers couriers={couriers} courierId={courierId} onChange={setPicked} disabled={locked} />
          {/* A refusal because the delivery moved closes this dialog: the board says why. */}
          <CommandMessage blocked={blocked} error={dispatch.error} what="a expedição" />
          <DialogFooter>
            <Button type="button" variant="outline" size="touch" onClick={onClose} disabled={locked}>
              Cancelar
            </Button>
            <Button
              type="submit"
              size="touch-primary"
              disabled={blocked || !courierId || dispatch.pending}
              aria-busy={dispatch.pending}
            >
              {awaitingRetry ? 'Tentar novamente' : 'Expedir'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

type CouriersProps = {
  couriers: ReturnType<typeof useCouriers>;
  courierId: string | undefined;
  onChange: (courierId: string) => void;
  disabled: boolean;
};

function Couriers({ couriers, courierId, onChange, disabled }: CouriersProps) {
  if (couriers.data) {
    if (couriers.data.length === 0) {
      return <p className="text-muted-foreground">Esta loja não tem estafetas ativos. Um gestor atribui-os no BackOffice.</p>;
    }
    const choices = couriers.data.map(({ id, code, name }) => ({ value: id, label: name, hint: code, icon: Bike }));
    return <ChoiceGroup legend="Estafeta" name="courier" choices={choices} value={courierId} onChange={onChange} disabled={disabled} />;
  }
  if (couriers.isError) {
    return (
      <div className="flex flex-col items-start gap-2">
        <p role="alert" className="text-error">
          {loadFailure(couriers.error, 'os estafetas')}
        </p>
        <Button type="button" variant="outline" size="touch" onClick={() => void couriers.refetch()}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  return <Skeleton role="status" aria-label="A carregar os estafetas…" className="h-28" />;
}
