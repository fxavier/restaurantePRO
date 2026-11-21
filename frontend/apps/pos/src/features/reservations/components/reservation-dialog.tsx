import { DialogDescription, DialogHeader, DialogTitle } from '@restaurantpro/ui';
import type { components } from '@restaurantpro/api-client';
import { CalendarPlus, Pencil } from 'lucide-react';
import { type FormEvent, type ReactNode, useState } from 'react';
import { useStoreTimeZone } from '../../../lib/auth/use-employee';
import { type Draft, draftOf, emptyDraft, isComplete, toRequest } from '../draft';
import { useBookReservation, useUpdateReservation } from '../hooks/use-reservation-commands';
import { refusal } from '../messages';
import type { ReservableArea, Reservation } from '../reservation';
import { CommandDialog, CommandFooter, giveUp } from './command-footer';
import { CustomerPicker } from './customer-picker';
import { ReservationForm } from './reservation-form';

type ReservationRequest = components['schemas']['com.xavier.backend.dining.model.dto.ReservationRequest'];

type Shared = { today: string; areas: ReservableArea[]; onClose: () => void };

/** Stitch's "Nova Reserva de Mesa": one intent per "Guardar reserva", the input locked while its outcome is unknown. */
export function NewReservationDialog({ date, ...shared }: Shared & { date: string }) {
  const book = useBookReservation({ onDone: shared.onClose });
  const awaitingRetry = !book.pending && book.intent !== undefined;
  return (
    <ReservationDialog
      {...shared}
      onClose={giveUp(book, shared.onClose)}
      icon={<CalendarPlus className="size-6" />}
      title="Nova reserva"
      initial={emptyDraft(date)}
      label="Guardar reserva"
      pending={book.pending}
      awaitingRetry={awaitingRetry}
      error={book.error && refusal(book.error, 'Sem ligação — não se sabe se a reserva ficou guardada.')}
      onSave={(request) => (awaitingRetry ? book.retry() : book.start(request, undefined))}
    />
  );
}

/**
 * The same form over a booked reservation; "Guardar alterações" replaces it with the version the form was filled
 * from, so an edit of a stale reservation is refused, never applied over another terminal's change.
 */
export function EditReservationDialog({ reservation, ...shared }: Shared & { reservation: Reservation }) {
  const timeZone = useStoreTimeZone();
  const update = useUpdateReservation(reservation.id, { onDone: shared.onClose });
  const [basedOn, setBasedOn] = useState(reservation.version);
  // Refused, and the list read again shows a newer version: the form restarts from it, and the host re-applies the
  // edit over what changed. The draft typed over the old version is dropped, never resent with the new one.
  if (update.isError && reservation.version !== basedOn) setBasedOn(reservation.version);
  return (
    <ReservationDialog
      key={basedOn}
      {...shared}
      icon={<Pencil className="size-6" />}
      title={`Editar a reserva de ${reservation.contactName}`}
      initial={draftOf(reservation, timeZone)}
      label="Guardar alterações"
      pending={update.isPending}
      awaitingRetry={false}
      error={
        update.error
          ? refusal(update.error, 'Sem ligação — não se sabe se a alteração ficou guardada. Confirme na lista antes de repetir.')
          : undefined
      }
      onSave={(request) => update.mutate({ ...request, version: basedOn })}
    />
  );
}

type Props = Shared & {
  icon: ReactNode;
  title: string;
  initial: Draft;
  label: string;
  pending: boolean;
  awaitingRetry: boolean;
  error: string | undefined;
  onSave: (request: ReservationRequest) => void;
};

function ReservationDialog({ icon, title, initial, label, pending, awaitingRetry, error, onSave, today, areas, onClose }: Props) {
  const timeZone = useStoreTimeZone();
  const [draft, setDraft] = useState(initial);
  const locked = pending || awaitingRetry;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (isComplete(draft)) onSave(toRequest(draft, timeZone));
  }

  return (
    <CommandDialog pending={pending} onClose={onClose} wide>
      <form onSubmit={submit} className="flex flex-col gap-4">
        <DialogHeader className="flex-row items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-12 shrink-0 items-center justify-center rounded-lg bg-primary text-primary-foreground"
          >
            {icon}
          </span>
          <div className="flex flex-col gap-1">
            <DialogTitle>{title}</DialogTitle>
            <DialogDescription>Registo informativo: pessoas, mesas e contacto. Não verifica a lotação.</DialogDescription>
          </div>
        </DialogHeader>
        <ReservationForm
          draft={draft}
          onChange={setDraft}
          today={today}
          areas={areas}
          locked={locked}
          customer={
            <CustomerPicker
              customerId={draft.customerId}
              contact={{ name: draft.contactName, phone: draft.contactPhone }}
              // Linking takes the record's contact; functional updates, as a created record links after its answer.
              onLink={({ id, name, phones }) =>
                setDraft((current) => ({
                  ...current,
                  customerId: id,
                  contactName: name,
                  contactPhone: phones[0] ?? current.contactPhone,
                }))
              }
              onUnlink={() => setDraft((current) => ({ ...current, customerId: null }))}
              locked={locked}
            />
          }
        />
        <CommandFooter
          label={label}
          error={error}
          pending={pending}
          awaitingRetry={awaitingRetry}
          disabled={!isComplete(draft)}
          onClose={onClose}
        />
      </form>
    </CommandDialog>
  );
}
