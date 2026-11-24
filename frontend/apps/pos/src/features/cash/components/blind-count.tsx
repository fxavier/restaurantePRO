import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  ConfirmButton,
} from '@restaurantpro/ui';
import { EyeOff, LockKeyhole } from 'lucide-react';
import { type FormEvent, useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { methodLabel, methodsToCount, parseAmount } from '../cash';
import type { SessionDetails } from '../hooks/use-cash';
import { useCloseSession } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { AmountField } from './amount-field';
import { CommandFeedback } from './command-feedback';

/**
 * The blind count (P23, CONTEXT "Contagem cega"): one amount per method the session saw, typed without seeing what
 * is expected, then "Fechar sessão" after a confirmation, since the count is final. The difference appears only in
 * the register's closing.
 */
export function BlindCount({ session, sessionId }: { session: SessionDetails; sessionId: string }) {
  const title = useId();
  const methods = methodsToCount(session.expected);
  const currency = session.openingFloat?.currency;
  const [typed, setTyped] = useState<Record<string, string>>({});
  const [confirming, setConfirming] = useState(false);
  const close = useCloseSession(sessionId);
  const blocked = useCommandsBlocked();
  const counts = methods.map((methodCode) => ({ methodCode, amount: parseAmount(typed[methodCode] ?? '') }));
  const complete = counts.every((count) => count.amount !== undefined);
  const awaitingRetry = !close.pending && close.intent !== undefined;
  const locked = close.pending || awaitingRetry;

  function submit(event: FormEvent) {
    event.preventDefault();
    if (blocked || !complete) return;
    if (awaitingRetry) close.retry();
    else setConfirming(true);
  }

  function confirm() {
    const lines = counts.flatMap(({ methodCode, amount }) => (amount === undefined ? [] : [{ methodCode, amount }]));
    close.start({ counts: lines, version: session.version ?? 0 }, undefined);
  }

  return (
    <form aria-labelledby={title} onSubmit={submit} className="flex flex-col gap-4 rounded-2xl border bg-card p-4">
      <div className="flex flex-col gap-1">
        <h3 id={title} className="flex items-center gap-2 text-xl font-semibold">
          <EyeOff aria-hidden="true" className="size-6 text-primary-hover" />
          Contagem cega
        </h3>
        <p className="text-muted-foreground">
          Conte o que tem em cada meio de pagamento e escreva o total. O valor esperado não é mostrado; a diferença
          aparece no fecho da caixa.
        </p>
      </div>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        {methods.map((code) => (
          <AmountField
            key={code}
            label={methodLabel(code)}
            currency={currency}
            value={typed[code] ?? ''}
            onChange={(value) => setTyped((current) => ({ ...current, [code]: value }))}
            readOnly={locked}
          />
        ))}
      </div>
      <CommandFeedback blocked={blocked} error={close.error && cashRefusal(close.error, 'o fecho da sessão')} />
      <ConfirmButton type="submit" size="touch-primary" className="rounded-xl" pending={close.pending} disabled={blocked || !complete}>
        <LockKeyhole aria-hidden="true" />
        {awaitingRetry ? 'Tentar novamente' : 'Fechar sessão'}
      </ConfirmButton>
      <AlertDialog open={confirming} onOpenChange={setConfirming}>
        <AlertDialogContent className="rounded-2xl">
          <AlertDialogHeader>
            <AlertDialogTitle>Fechar a sessão de caixa?</AlertDialogTitle>
            <AlertDialogDescription>
              A contagem fica registada tal como a escreveu e não pode ser alterada depois.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel size="touch" className="rounded-xl bg-card">Voltar</AlertDialogCancel>
            <AlertDialogAction size="touch" className="rounded-xl" onClick={confirm}>
              Confirmar fecho
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </form>
  );
}
