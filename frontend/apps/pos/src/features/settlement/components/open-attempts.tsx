import { Button, Input, Label, Money } from '@restaurantpro/ui';
import { CircleHelp, Hourglass } from 'lucide-react';
import { useId, useState } from 'react';
import { useCancelAttempt, useConfirmAttempt, useResolveAttempt } from '../hooks/use-settlement-commands';
import type { OpenAttempt } from '../settlement';
import { CommandStatus } from './command-status';

type Props = {
  orderId: string;
  attempts: OpenAttempt[];
  /** The label of each attempt's target ("Parte 2 de 3", "Conta total"). */
  targetLabel: (attempt: OpenAttempt) => string;
  disabled: boolean;
};

/**
 * Stitch's "Status Terminal Integrado": the bill's payments still open. One in doubt (UNKNOWN) is pending
 * reconciliation, never shown as failed (§7.4); a card terminal's waits for the operator's confirmation.
 */
export function OpenAttempts({ orderId, attempts, targetLabel, disabled }: Props) {
  if (attempts.length === 0) return null;
  return (
    <section aria-labelledby="attempts-title" className="flex flex-col gap-3 rounded-xl border bg-card p-4">
      <h3 id="attempts-title" className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
        Pagamentos em curso
      </h3>
      <ul className="flex flex-col gap-3">
        {attempts.map((attempt) => (
          <AttemptRow key={attempt.id} orderId={orderId} attempt={attempt} target={targetLabel(attempt)} disabled={disabled} />
        ))}
      </ul>
    </section>
  );
}

function AttemptRow({ orderId, attempt, target, disabled }: { orderId: string; attempt: OpenAttempt; target: string; disabled: boolean }) {
  const resolve = useResolveAttempt(orderId);
  const confirm = useConfirmAttempt(orderId);
  const cancel = useCancelAttempt(orderId);
  const [code, setCode] = useState('');
  const codeId = useId();
  const busy = disabled || [resolve, confirm, cancel].some((command) => command.intent !== undefined);
  const awaitingOperator = attempt.status === 'PENDING' && attempt.methodKind === 'EXTERNAL_TERMINAL';

  return (
    <li className="flex flex-col gap-3 rounded-lg bg-muted p-3">
      <div className="flex items-start gap-3">
        {awaitingOperator ? (
          <Hourglass aria-hidden="true" className="size-6 shrink-0 text-warning" />
        ) : (
          <CircleHelp aria-hidden="true" className="size-6 shrink-0 text-warning" />
        )}
        <div className="flex min-w-0 flex-1 flex-col">
          <span className="font-semibold">{statusText(attempt)}</span>
          <span className="text-sm text-muted-foreground">
            {attempt.methodCode} · {target}
          </span>
        </div>
        <Money value={attempt.amount} className="text-lg font-bold" />
      </div>
      {awaitingOperator ? (
        <div className="flex flex-wrap items-end gap-2">
          <div className="flex min-w-40 flex-1 flex-col gap-1">
            <Label htmlFor={codeId}>Código de autorização</Label>
            <Input id={codeId} autoComplete="off" value={code} onChange={(event) => setCode(event.target.value)} className="h-12" />
          </div>
          <Button
            size="touch"
            disabled={busy}
            onClick={() => confirm.start({ attemptId: attempt.id, authorizationCode: code.trim() || undefined }, undefined)}
          >
            Confirmar pagamento
          </Button>
          <Button
            variant="destructive"
            size="touch"
            disabled={busy}
            onClick={() => cancel.start({ attemptId: attempt.id, reason: 'Não aprovado no terminal' }, undefined)}
          >
            Cancelar pagamento
          </Button>
        </div>
      ) : (
        <Button variant="outline" size="touch" disabled={busy} onClick={() => resolve.start(attempt.id, undefined)}>
          Consultar o estado
        </Button>
      )}
      {[resolve, confirm, cancel].map((command, index) => (
        <CommandStatus
          key={index}
          command={command}
          pendingText="A atualizar o pagamento…"
          unconfirmedText="Sem ligação — o pedido não foi confirmado."
          refusedText="Não foi possível."
          blocked={disabled}
        />
      ))}
    </li>
  );
}

function statusText(attempt: OpenAttempt): string {
  if (attempt.status === 'UNKNOWN') return 'Por confirmar — reconciliação pendente';
  if (attempt.status === 'PENDING' && attempt.methodKind === 'EXTERNAL_TERMINAL') return 'A aguardar o terminal de pagamento';
  return 'Em processamento pelo fornecedor';
}
