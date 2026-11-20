import { ConfirmButton } from '@restaurantpro/ui';
import { type FormEvent, useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { parseAmount } from '../cash';
import type { RegisterSummary } from '../hooks/use-cash';
import { useOpenSession } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { AmountField } from './amount-field';
import { CommandFeedback } from './command-feedback';
import { RegisterChoice } from './register-choice';

/**
 * No session open (§4): the operator chooses a register and types the opening float, then "Abrir sessão". The screen
 * shows the session once the server has opened it.
 */
export function OpenSessionForm({ registers }: { registers: RegisterSummary[] }) {
  const title = useId();
  const [registerId, setRegisterId] = useState<string>();
  const [float, setFloat] = useState('');
  const open = useOpenSession();
  const blocked = useCommandsBlocked();
  const amount = parseAmount(float);
  // An unknown outcome keeps its intent: only "Tentar novamente" (same key) may follow, with the same values.
  const awaitingRetry = !open.pending && open.intent !== undefined;
  const locked = open.pending || awaitingRetry;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !registerId || amount === undefined) return;
    if (awaitingRetry) open.retry();
    else open.start({ registerId, openingFloat: amount }, undefined);
  }

  return (
    <form aria-labelledby={title} onSubmit={submit} className="flex flex-col gap-4 rounded-xl border bg-card p-4 lg:p-6">
      <div className="flex flex-col gap-1">
        <h3 id={title} className="text-xl font-semibold">
          Abrir sessão de caixa
        </h3>
        <p className="text-muted-foreground">Não tem nenhuma sessão de caixa aberta. Escolha a caixa e conte o fundo inicial.</p>
      </div>
      <RegisterChoice name="open-register" registers={registers} value={registerId} onChange={setRegisterId} disabled={locked} />
      <AmountField label="Fundo de caixa inicial" value={float} onChange={setFloat} readOnly={locked} />
      <CommandFeedback blocked={blocked} error={open.error && cashRefusal(open.error, 'a abertura da sessão')} />
      <ConfirmButton
        type="submit"
        size="touch-primary"
        pending={open.pending}
        disabled={blocked || !registerId || amount === undefined}
      >
        {awaitingRetry ? 'Tentar novamente' : 'Abrir sessão'}
      </ConfirmButton>
    </form>
  );
}
