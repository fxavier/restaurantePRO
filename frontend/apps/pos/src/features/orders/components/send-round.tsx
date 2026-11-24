import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { RotateCcw } from 'lucide-react';
import type { ReactNode } from 'react';
import { errorMessage, isVersionConflict } from '../../../lib/error-messages';
import { isInProgress } from '../../../lib/use-intent';
import type { useConfirmBatch } from '../hooks/use-confirm-batch';

type Props = {
  send: ReturnType<typeof useConfirmBatch>;
  /** Nothing to send, a command still in flight, or "Sem ligação". */
  disabled: boolean;
  /** "Sem ligação": even "Tentar novamente" waits for the connection. */
  blocked: boolean;
  /** The round the server confirmed last, as it answered. */
  sentBatchNo?: number | null;
  onSend: () => void;
  /** The button's name, which a refusal also names: "Pedir" on the order screen, "Enviar ronda N" on the table map. */
  label?: string;
  /** Another command's outcome, in the same two-line place (the table map's "Subtotal"), so no line is kept twice. */
  otherStatus?: ReactNode;
  /** Lines kept for the outcome: two on the order screen; one on the table map's compact panel. */
  statusLines?: 1 | 2;
};

/**
 * "Pedir", the order screen's one primary action (DS: 56 px), and one fixed place below it for what the last press
 * did. An unknown outcome (no answer, or the key still running) keeps its intent: "Tentar novamente" resends the same
 * key, "Descartar" gives it up. Nothing here says a round was sent before the server has.
 */
export function SendRound({
  send,
  disabled,
  blocked,
  sentBatchNo,
  onSend,
  label = 'Pedir',
  otherStatus,
  statusLines = 2,
}: Props) {
  const awaitingRetry = send.intent !== undefined && send.error !== undefined && !send.pending;
  return (
    <div className="flex flex-col gap-1">
      {awaitingRetry ? (
        <div className="flex gap-2">
          {/* Both 56 px, and narrow enough to share the panel's 328 px. */}
          <Button size="touch-primary" className="min-w-0 flex-1 px-4" onClick={send.retry} disabled={blocked}>
            <RotateCcw aria-hidden="true" />
            Tentar novamente
          </Button>
          <Button size="touch-primary" variant="outline" className="px-4 text-base font-medium" onClick={send.discard}>
            Descartar
          </Button>
        </div>
      ) : (
        <Button size="touch-primary" className="w-full" disabled={disabled || send.intent !== undefined} onClick={onSend}>
          {label}
        </Button>
      )}
      {/* Two lines high whatever it says, so nothing above moves when it changes. */}
      <div className={`${statusLines === 1 ? 'min-h-5' : 'min-h-10'} text-sm`}>
        <SendStatus send={send} sentBatchNo={sentBatchNo} label={label} />
        {otherStatus}
      </div>
    </div>
  );
}

function SendStatus({ send, sentBatchNo, label }: Pick<Props, 'send' | 'sentBatchNo'> & { label: string }) {
  if (send.pending) {
    return (
      <p role="status" aria-label="A enviar" className="text-muted-foreground">
        A enviar a ronda…
      </p>
    );
  }
  if (send.error) {
    const text = send.intent
      ? isInProgress(send.error)
        ? 'A ronda ainda está a ser processada pelo servidor.'
        : 'Sem ligação — a ronda não foi confirmada.'
      : refusal(send.error, label);
    return (
      <p role="alert" className="font-medium text-error">
        {text}
      </p>
    );
  }
  if (sentBatchNo !== undefined) {
    return (
      <p role="status" aria-label="Envio" className="text-muted-foreground">
        {sentBatchNo === null ? 'Ronda enviada.' : `Ronda ${sentBatchNo} enviada.`}
      </p>
    );
  }
  return null;
}

/** A definitive answer: the round was not sent, and the order (and catalog) is read again to show why. */
function refusal(error: Error, label: string): string {
  if (isVersionConflict(error)) return `O pedido foi alterado noutro terminal. Reveja-o e carregue em ${label} de novo.`;
  if (!(error instanceof ApiError)) return 'A ronda não foi enviada. A resposta do servidor está incompleta.';
  if (error.code === 'CATALOG_CHANGED') return `O catálogo foi atualizado. Reveja os novos valores e carregue em ${label} de novo.`;
  if (error.code === 'PRICES_CHANGED' || error.code === 'PROMOTIONS_CHANGED') {
    return `Os preços mudaram. Reveja os novos valores e carregue em ${label} de novo.`;
  }
  return `A ronda não foi enviada. ${errorMessage(error)}`;
}
