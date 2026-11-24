import { DialogDescription, DialogHeader, DialogTitle, Label } from '@restaurantpro/ui';
import { type FormEvent, useState } from 'react';
import { useCancelReservation, useMarkNoShow } from '../hooks/use-reservation-commands';
import { refusal } from '../messages';
import type { Reservation } from '../reservation';
import { CommandDialog, CommandFooter, giveUp } from './command-footer';

type Props = { reservation: Reservation; onClose: () => void };

/** ReservationCancelRequest: the reason has at most 500 characters. */
const REASON_MAX = 500;

/** BOOKED → CANCELLED (§8.3), with the reason the backend keeps; the version read guards a concurrent change (D17). */
export function CancelDialog({ reservation, onClose }: Props) {
  const [reason, setReason] = useState('');
  const cancel = useCancelReservation(reservation.id, { onDone: onClose });
  const awaitingRetry = !cancel.pending && cancel.intent !== undefined;
  const close = giveUp(cancel, onClose);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (awaitingRetry) cancel.retry();
    else cancel.start({ reason: reason.trim(), version: reservation.version }, undefined);
  }

  return (
    <CommandDialog pending={cancel.pending} onClose={close}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>Cancelar a reserva de {reservation.contactName}</DialogTitle>
          <DialogDescription>A reserva fica cancelada, com o motivo indicado.</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-2">
          <Label htmlFor="cancel-reason">Motivo</Label>
          <textarea
            id="cancel-reason"
            value={reason}
            maxLength={REASON_MAX}
            rows={3}
            readOnly={cancel.pending || awaitingRetry}
            onChange={(event) => setReason(event.target.value)}
            className="rounded-xl border border-input bg-card px-3 py-2 text-base outline-none focus-visible:ring-2 focus-visible:ring-ring"
          />
        </div>
        <CommandFooter
          label="Cancelar reserva"
          variant="destructive"
          error={cancel.error && refusal(cancel.error, 'Sem ligação — não se sabe se a reserva foi cancelada.')}
          pending={cancel.pending}
          awaitingRetry={awaitingRetry}
          disabled={reason.trim() === ''}
          onClose={close}
        />
      </form>
    </CommandDialog>
  );
}

/** BOOKED → NO_SHOW (§8.3): the party did not come; the server refuses it before the start (P47). */
export function NoShowDialog({ reservation, onClose }: Props) {
  const noShow = useMarkNoShow(reservation.id, { onDone: onClose });
  const awaitingRetry = !noShow.pending && noShow.intent !== undefined;
  const close = giveUp(noShow, onClose);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (awaitingRetry) noShow.retry();
    else noShow.start({ version: reservation.version }, undefined);
  }

  return (
    <CommandDialog pending={noShow.pending} onClose={close}>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader>
          <DialogTitle>Não comparência de {reservation.contactName}</DialogTitle>
          <DialogDescription>A reserva fica registada como não comparência e deixa de estar por chegar.</DialogDescription>
        </DialogHeader>
        <CommandFooter
          label="Registar não comparência"
          variant="destructive"
          error={noShow.error && refusal(noShow.error, 'Sem ligação — não se sabe se a não comparência ficou registada.')}
          pending={noShow.pending}
          awaitingRetry={awaitingRetry}
          onClose={close}
        />
      </form>
    </CommandDialog>
  );
}
