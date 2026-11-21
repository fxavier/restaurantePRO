import { useRef } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import type { FinanceCommand } from '../finance-commands';
import { useFinanceCommand } from './use-finance-commands';
import { useFinanceStepUp } from './use-finance-step-up';

type Handlers = { onSuccess?: () => void; onError?: (error: Error) => void };

/**
 * A finance command with its step-up (FC §6): `send` runs it, a step-up challenge opens `dialog`, and `failure` is
 * what to explain otherwise, with "Tentar novamente" resending the same command (same key, FC §8).
 */
export function useSensitiveCommand(storeId: string) {
  const command = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);
  // The retry resends under the handlers of the intent it repeats (a form still closes on success).
  const last = useRef<Handlers>({});

  const send = (next: FinanceCommand, handlers: Handlers = last.current) => {
    last.current = handlers;
    command.mutate(next, {
      onSuccess: handlers.onSuccess,
      onError: (error) => {
        if (isStepUpRequired(error)) stepUp.prompt(error, next);
        else handlers.onError?.(error);
      },
    });
  };
  const retry = () => command.variables && send(command.variables);
  const error = command.error && !isStepUpRequired(command.error) ? command.error : null;

  return {
    send,
    pending: command.isPending,
    error,
    failure: error ? { error, pending: command.isPending, onRetry: retry } : undefined,
    dialog: stepUp.dialog,
  };
}
