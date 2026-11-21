import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import { commandTab, sendCommand, type FinanceCommand } from '../finance-commands';
import { CASH_KEY, METHODS_KEY, SERIES_KEY } from './use-reports';

const KEYS = { fechos: CASH_KEY, fiscal: SERIES_KEY, meios: METHODS_KEY } as const;

/**
 * Sends one finance command. Mutations never retry (FC §8): "Tentar novamente" resends `variables`, the same command
 * under the same key. Settled either way, the reads it changes are fetched again, so a 409 shows what changed.
 */
export function useFinanceCommand() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (command: FinanceCommand) => sendCommand(api, command),
    onSettled: (_data, _error, command) =>
      queryClient.invalidateQueries({ queryKey: KEYS[commandTab(command) as keyof typeof KEYS] }),
  });
}

/** One reopen intent: the reason, the approving employee and the key. The PIN lives only in this request. */
export type Reopening = { dayId: string; reason: string; approverCode: string; approverPin: string; idempotencyKey: string };

/** Reopens a closed day (D40): never kept for a step-up and never logged, as it carries the approver's PIN. */
export function useReopenDay() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ dayId, reason, approverCode, approverPin, idempotencyKey }: Reopening) =>
      sendWaitingWhileInProgress(() =>
        api.POST('/api/v1/backoffice/cash/days/{id}/reopen', {
          params: {
            path: { id: dayId },
            header: { 'Idempotency-Key': idempotencyKey, 'X-Approver-Code': approverCode, 'X-Approver-Pin': approverPin },
          },
          body: { reason },
        }),
      ),
    onSettled: () => queryClient.invalidateQueries({ queryKey: CASH_KEY }),
    gcTime: 0,
  });
}
