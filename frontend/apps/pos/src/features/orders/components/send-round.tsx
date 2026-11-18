import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { RotateCcw, X } from 'lucide-react';
import { errorMessage } from '../../../lib/error-messages';
import { isInProgress } from '../../../lib/use-intent';
import { StaleOrderError, type useConfirmBatch } from '../hooks/use-confirm-batch';

type Props = {
  send: ReturnType<typeof useConfirmBatch>;
  /** Nothing to send, a command still in flight, or "Sem ligação". */
  disabled: boolean;
  /** "Sem ligação": even "Tentar novamente" waits for the connection. */
  blocked: boolean;
  /** The round the server confirmed last, as it answered. */
  sentBatchNo?: number | null;
  onSend: () => void;
};

/**
 * "Pedir", the order screen's one primary action (DS: 56 px), and one fixed place below it for what the last press
 * did. An unknown outcome (no answer, or the key still running) keeps its intent: "Tentar novamente" resends the same
 * key, "Descartar" gives it up. Nothing here says a round was sent before the server has.
 */
export function SendRound({ send, disabled, blocked, sentBatchNo, onSend }: Props) {
  const awaitingRetry = send.intent !== undefined && send.error !== undefined && !send.pending;
  return (
    <div className="flex flex-col gap-1">
      {awaitingRetry ? (
        <div className="flex gap-2">
          <Button size="touch-primary" className="flex-1" onClick={send.retry} disabled={blocked}>
            <RotateCcw aria-hidden="true" />
            Tentar novamente
          </Button>
          <Button size="touch-primary" variant="outline" onClick={send.discard}>
            <X aria-hidden="true" />
            Descartar
          </Button>
        </div>
      ) : (
        <Button size="touch-primary" className="w-full" disabled={disabled || send.intent !== undefined} onClick={onSend}>
          Pedir
        </Button>
      )}
      {/* Two lines high whatever it says, so nothing above moves when it changes. */}
      <div className="min-h-10 text-sm">
        <SendStatus send={send} sentBatchNo={sentBatchNo} />
      </div>
    </div>
  );
}

function SendStatus({ send, sentBatchNo }: Pick<Props, 'send' | 'sentBatchNo'>) {
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
      : refusal(send.error);
    return (
      <p role="alert" className="font-medium text-destructive">
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
function refusal(error: Error): string {
  if (error instanceof StaleOrderError) return 'O pedido foi alterado noutro terminal. Reveja-o e carregue em Pedir de novo.';
  if (!(error instanceof ApiError)) return 'A ronda não foi enviada. A resposta do servidor está incompleta.';
  if (error.code === 'CATALOG_CHANGED') return 'O catálogo foi atualizado. Reveja os novos valores e carregue em Pedir de novo.';
  if (error.code === 'PRICES_CHANGED' || error.code === 'PROMOTIONS_CHANGED') {
    return 'Os preços mudaram. Reveja os novos valores e carregue em Pedir de novo.';
  }
  return `A ronda não foi enviada. ${errorMessage(error)}`;
}
