import { cn, DialogDescription, DialogHeader, DialogTitle } from '@restaurantpro/ui';
import { type FormEvent, useState } from 'react';
import { useSeat } from '../hooks/use-reservation-commands';
import { guestsLabel, seatsLabel } from '../labels';
import { refusal } from '../messages';
import type { ReservableArea, Reservation } from '../reservation';
import { CommandDialog, CommandFooter, giveUp } from './command-footer';

type Props = {
  reservation: Reservation;
  areas: ReservableArea[];
  onClose: () => void;
  onSeated: (orderId: string) => void;
};

/**
 * Seating a booked reservation (D126): one of the store's free, unlocked tables, the reservation's own first when one
 * is free (its tables are informative: any table may seat it), opened for its party.
 */
export function SeatDialog({ reservation, areas, onClose, onSeated }: Props) {
  const free = areas
    .map((area) => ({ ...area, tables: area.tables.filter((table) => table.status === 'FREE' && !table.locked) }))
    .filter((area) => area.tables.length > 0);
  const freeIds = new Set(free.flatMap((area) => area.tables.map((table) => table.id)));
  const [tableId, setTableId] = useState(() => reservation.tableIds.find((id) => freeIds.has(id)));
  const seat = useSeat({ onSeated });
  const awaitingRetry = !seat.pending && seat.intent !== undefined;
  const locked = seat.pending || awaitingRetry;
  const close = giveUp(seat, onClose);

  function submit(event: FormEvent) {
    event.preventDefault();
    if (awaitingRetry) seat.retry();
    else if (tableId) seat.start({ tableId, guests: reservation.partySize, reservationId: reservation.id }, undefined);
  }

  return (
    <CommandDialog pending={seat.pending} onClose={close}>
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Sentar {reservation.contactName}</DialogTitle>
            <DialogDescription>
              Abre a mesa escolhida para <span className="font-semibold text-foreground">{guestsLabel(reservation.partySize)}</span>.
            </DialogDescription>
          </DialogHeader>
          {free.length === 0 ? (
            <p className="py-4 text-center text-muted-foreground">Não há mesas livres. Liberte uma mesa no mapa de sala.</p>
          ) : (
            <div className="flex max-h-80 flex-col gap-4 overflow-y-auto">
              {free.map((area) => (
                <fieldset key={area.id} className="flex flex-col gap-2">
                  <legend className="mb-2 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                    {area.name}
                  </legend>
                  <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                    {area.tables.map((table) => (
                      <label
                        key={table.id}
                        className={cn(
                          'flex min-h-14 cursor-pointer items-center gap-3 rounded-lg border-2 bg-muted px-3 has-focus-visible:ring-2 has-focus-visible:ring-ring',
                          table.id === tableId ? 'border-primary' : 'border-border',
                          locked && 'cursor-not-allowed opacity-50',
                        )}
                      >
                        <input
                          type="radio"
                          name="table"
                          value={table.id}
                          checked={table.id === tableId}
                          onChange={() => setTableId(table.id)}
                          disabled={locked}
                          className="size-5 accent-primary"
                        />
                        <span className="text-lg font-semibold">{table.code}</span>
                        <span className="text-sm text-muted-foreground">{seatsLabel(table.seats)}</span>
                      </label>
                    ))}
                  </div>
                </fieldset>
              ))}
            </div>
          )}
          <CommandFooter
            label="Sentar"
            error={seat.error && refusal(seat.error, 'Sem ligação — não se sabe se a mesa foi aberta.')}
            pending={seat.pending}
            awaitingRetry={awaitingRetry}
            disabled={!tableId}
            onClose={close}
          />
        </form>
    </CommandDialog>
  );
}
