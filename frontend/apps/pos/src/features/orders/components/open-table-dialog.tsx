import { ApiError } from '@restaurantpro/api-client';
import {
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
} from '@restaurantpro/ui';
import { WifiOff } from 'lucide-react';
import { type FormEvent, useState } from 'react';
import { PinPad } from '../../identity/components/pin-pad';
import { errorMessage, isOffline, OFFLINE } from '../../../lib/error-messages';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { isInProgress } from '../../../lib/use-intent';
import type { DiningTable } from '../../dining/table-map';
import { useOpenTable } from '../hooks/use-order-commands';

const LOCKED = 'A mesa está bloqueada. Um gerente tem de a desbloquear antes de a abrir.';
/** OpenTableRequest: 1 to 999 guests. */
const MAX_DIGITS = 3;

type Props = { table: DiningTable; onClose: () => void; onOpened: (orderId: string) => void };

/**
 * Opening a free table (§4, PRD 36): the guests on a numeric pad (or a keyboard), then "Abrir mesa". A table the map
 * shows locked is refused at once, with the reason; one locked or taken meanwhile is refused by the server.
 */
export function OpenTableDialog({ table, onClose, onOpened }: Props) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent showCloseButton={false} className="sm:max-w-md">
        {table.locked ? (
          <>
            <DialogHeader>
              <DialogTitle>Mesa {table.code} bloqueada</DialogTitle>
              <DialogDescription className="text-base text-foreground">{LOCKED}</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button variant="outline" size="touch" onClick={onClose}>
                Fechar
              </Button>
            </DialogFooter>
          </>
        ) : (
          <GuestsForm table={table} onClose={onClose} onOpened={onOpened} />
        )}
      </DialogContent>
    </Dialog>
  );
}

function GuestsForm({ table, onClose, onOpened }: Props) {
  const [guests, setGuests] = useState('');
  const open = useOpenTable(table.id, onOpened);
  const blocked = useCommandsBlocked();
  const count = Number(guests);
  const valid = guests !== '' && count >= 1;
  // An unknown outcome keeps its intent: only "Tentar novamente" (same key) may follow, with the same guests.
  const awaitingRetry = !open.pending && open.intent !== undefined;
  const locked = open.pending || awaitingRetry;

  const type = (next: string) => {
    if (!locked) setGuests(next.replace(/\D/g, '').replace(/^0+/, '').slice(0, MAX_DIGITS));
  };

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !valid) return;
    if (awaitingRetry) open.retry();
    else open.start(count, undefined);
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <DialogHeader>
        <DialogTitle>Abrir mesa {table.code}</DialogTitle>
        <DialogDescription>Indique o número de pessoas.</DialogDescription>
      </DialogHeader>
      <Input
        aria-label="Número de pessoas"
        inputMode="numeric"
        autoComplete="off"
        value={guests}
        onChange={(event) => type(event.target.value)}
        readOnly={locked}
        className="h-14 text-center text-3xl font-semibold tabular-nums md:text-3xl"
      />
      <div className="self-center">
        <PinPad
          onDigit={(digit) => type(guests + digit)}
          onDelete={() => type(guests.slice(0, -1))}
          onClear={() => type('')}
          disabled={locked}
        />
      </div>
      {/* One line kept for the message, so the buttons never move when one appears. */}
      <div className="min-h-12">
        {blocked ? (
          <p className="flex items-center gap-2 font-medium text-destructive">
            <WifiOff aria-hidden="true" className="size-5 shrink-0" />
            {OFFLINE}
          </p>
        ) : open.error ? (
          <p role="alert" className="font-medium text-destructive">
            {refusal(open.error)}
          </p>
        ) : null}
      </div>
      <DialogFooter>
        <Button type="button" variant="outline" size="touch" onClick={onClose} disabled={open.pending}>
          Cancelar
        </Button>
        <Button type="submit" size="touch-primary" disabled={blocked || !valid || open.pending} aria-busy={open.pending}>
          {awaitingRetry ? 'Tentar novamente' : 'Abrir mesa'}
        </Button>
      </DialogFooter>
    </form>
  );
}

function refusal(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível abrir a mesa. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — a abertura da mesa não foi confirmada.';
  if (isInProgress(error)) return 'O servidor ainda está a abrir esta mesa. Tente novamente dentro de instantes.';
  if (error.code === 'TABLE_LOCKED') return LOCKED;
  if (error.code === 'TABLE_OCCUPIED') return 'A mesa acabou de ser aberta noutro terminal. O mapa foi atualizado.';
  return `Não foi possível abrir a mesa. ${errorMessage(error)}`;
}
