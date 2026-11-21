import { ApiError } from '@restaurantpro/api-client';
import { Button } from '@restaurantpro/ui';
import { RotateCcw, X } from 'lucide-react';
import { errorMessage, isVersionConflict } from '../../../lib/error-messages';
import { isInProgress } from '../../../lib/use-intent';
import { SETTLEMENT_MESSAGES } from '../messages';

/** What a settlement command shows of itself (`useIntent`). */
export type Command = {
  pending: boolean;
  error?: Error;
  /** Still set after a failure: its outcome is unknown, and "Tentar novamente" resends the same key. */
  intent?: unknown;
  retry(): void;
  discard(): void;
};

type Props = {
  command: Command;
  /** "A dividir a conta…" */
  pendingText: string;
  /** "Sem ligação — a divisão não foi confirmada." */
  unconfirmedText: string;
  /** "A divisão não foi feita." */
  refusedText: string;
  /** "Sem ligação": even the retry waits for the connection. */
  blocked: boolean;
  /**
   * Whether an unknown outcome may be given up. A payment may not: giving it up and charging again could charge twice
   * (§7.4), so it is only resent, with its key, until the server answers.
   */
  discardable?: boolean;
};

/**
 * One fixed place for what the last press of a settlement command did: in flight, an unknown outcome (the same intent
 * may be resent, never a new one), or why the server refused it. Nothing here says it happened before the server has.
 */
export function CommandStatus({ command, pendingText, unconfirmedText, refusedText, blocked, discardable = true }: Props) {
  if (command.pending) {
    return (
      <p role="status" className="text-muted-foreground">
        {pendingText}
      </p>
    );
  }
  if (!command.error) return null;
  if (command.intent !== undefined) {
    return (
      <div role="alert" className="flex flex-wrap items-center gap-2">
        <p className="flex-1 font-medium text-error">
          {isInProgress(command.error) ? 'O servidor ainda está a processar este pedido.' : unconfirmedText}
        </p>
        <Button variant="outline" size="touch" onClick={command.retry} disabled={blocked}>
          <RotateCcw aria-hidden="true" />
          Tentar novamente
        </Button>
        {discardable && (
          <Button variant="ghost" size="touch" onClick={command.discard}>
            <X aria-hidden="true" />
            Descartar
          </Button>
        )}
      </div>
    );
  }
  return (
    <p role="alert" className="font-medium text-error">
      {refusedText} {refusal(command.error)}
    </p>
  );
}

/** A definitive answer; the bill has been read again, so what changed is on screen. */
export function refusal(error: Error): string {
  if (isVersionConflict(error)) return 'A conta foi alterada noutro terminal. Reveja-a e tente de novo.';
  if (!(error instanceof ApiError)) return 'A resposta do servidor está incompleta.';
  return errorMessage(error, { messages: SETTLEMENT_MESSAGES });
}
