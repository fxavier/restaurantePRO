import { ApiError } from '@restaurantpro/api-client';
import { ConfirmButton } from '@restaurantpro/ui';
import { ClipboardCheck } from 'lucide-react';
import { useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import type { RegisterSummary } from '../hooks/use-cash';
import { type ClosingView, useCreateClosing } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { ClosingDetails } from './closing-details';
import { CommandFeedback } from './command-feedback';
import { RegisterChoice } from './register-choice';

type Props = {
  registers: RegisterSummary[];
  /** The business day the closing is for (`GET /cash/days/current`). */
  dayId: string;
};

/**
 * The register's closing for the day (§4, §8.3), once its sessions are closed: the register chosen, "Preparar fecho"
 * drafts it on the server, which compares what was expected with the blind counts.
 */
export function RegisterClosing({ registers, dayId }: Props) {
  const title = useId();
  const [registerId, setRegisterId] = useState<string>();
  const [closing, setClosing] = useState<ClosingView>();
  const create = useCreateClosing(setClosing);
  const blocked = useCommandsBlocked();
  const awaitingRetry = !create.pending && create.intent !== undefined;

  function prepare() {
    if (blocked || !registerId) return;
    if (awaitingRetry) create.retry();
    else create.start({ cashDayId: dayId, registerId }, undefined);
  }

  return (
    <section aria-labelledby={title} className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
      <div className="flex flex-col gap-1">
        <h3 id={title} className="flex items-center gap-2 text-xl font-semibold">
          <ClipboardCheck aria-hidden="true" className="size-6 text-primary-hover" />
          Fecho da caixa
        </h3>
        <p className="text-muted-foreground">
          Depois de fechadas as sessões, prepare o fecho da caixa: o servidor compara o esperado com as contagens.
        </p>
      </div>
      {closing?.id ? (
        <ClosingDetails closing={{ ...closing, id: closing.id }} onClosing={setClosing} />
      ) : (
        <>
          <RegisterChoice
            name="closing-register"
            registers={registers}
            value={registerId}
            onChange={setRegisterId}
            disabled={create.pending || awaitingRetry}
          />
          <CommandFeedback blocked={blocked} error={create.error && refusal(create.error)} />
          <ConfirmButton
            type="button"
            size="touch"
            className="rounded-xl"
            pending={create.pending}
            disabled={blocked || !registerId}
            onClick={prepare}
          >
            {awaitingRetry ? 'Tentar novamente' : 'Preparar fecho'}
          </ConfirmButton>
        </>
      )}
    </section>
  );
}

/**
 * Drafting refuses with `ILLEGAL_CASH_STATE` when the register already has a live closing for the day (a DRAFT this
 * screen lost on navigation or reload, or a SUBMITTED one). The POS has no read of it yet, so the text says what to do.
 */
function refusal(error: Error): string {
  if (error instanceof ApiError && error.code === 'ILLEGAL_CASH_STATE') {
    return (
      'Esta caixa já tem um fecho em curso neste dia (em rascunho ou submetido), que este terminal não consegue reabrir. ' +
      'Se foi submetido, aguarde a decisão do gerente; se ficou em rascunho, peça ajuda ao gerente.'
    );
  }
  return cashRefusal(error, 'a preparação do fecho');
}
