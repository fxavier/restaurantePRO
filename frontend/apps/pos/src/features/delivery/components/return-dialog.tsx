import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@restaurantpro/ui';
import { type FormEvent, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { type Delivery, intentState } from '../delivery';
import { useDeliveryTransition } from '../hooks/use-delivery-transition';
import { CommandMessage } from './command-message';
import { TextField } from './text-field';

/** ReturnedRequest: a reason of at most 300 characters. */
const MAX_REASON = 300;

type Props = { delivery: Delivery; onClose: () => void; onChangedElsewhere: () => void };

/** A delivery back from the road (OUT_FOR_DELIVERY → RETURNED) with why; it may then go out again. */
export function ReturnDialog({ delivery, onClose, onChangedElsewhere }: Props) {
  const [reason, setReason] = useState('');
  const blocked = useCommandsBlocked();
  const returned = useDeliveryTransition(delivery.orderId, {
    onDone: onClose,
    onChangedElsewhere() {
      onChangedElsewhere();
      onClose();
    },
  });
  const { awaitingRetry, locked } = intentState(returned);
  const valid = reason.trim() !== '';

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !valid) return;
    if (awaitingRetry) returned.retry();
    else returned.start({ kind: 'returned', reason: reason.trim(), version: delivery.version }, undefined);
  }

  return (
    <Dialog open onOpenChange={(open) => !open && !returned.pending && onClose()}>
      <DialogContent showCloseButton={false} className="sm:max-w-md">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Devolver a entrega n.º {delivery.orderNumber}</DialogTitle>
            <DialogDescription>Indique porque é que a entrega voltou à loja.</DialogDescription>
          </DialogHeader>
          <TextField label="Motivo" maxLength={MAX_REASON} value={reason} readOnly={locked} onChange={setReason} />
          {/* A refusal because the delivery moved closes this dialog: the board says why. */}
          <CommandMessage blocked={blocked} error={returned.error} what="a devolução" />
          <DialogFooter>
            <Button type="button" variant="outline" size="touch" onClick={onClose} disabled={returned.pending}>
              Cancelar
            </Button>
            <Button type="submit" size="touch-primary" disabled={blocked || !valid || returned.pending} aria-busy={returned.pending}>
              {awaitingRetry ? 'Tentar novamente' : 'Registar devolução'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}
