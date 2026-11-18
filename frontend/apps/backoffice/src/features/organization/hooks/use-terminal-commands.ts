import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { createTerminal, issueEnrolmentCode, revokeTerminal } from '../terminal-commands';
import type { TerminalCreateRequest } from '../types';
import { TERMINALS_KEY } from './use-terminals';

/**
 * Mutations never retry (FC §8): "Tentar novamente" resends `variables`, the same request under the same
 * Idempotency-Key. The caller makes the key when the user starts the intent.
 */
export function useCreateTerminal() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: TerminalCreateRequest; idempotencyKey: string }) =>
      createTerminal(api, body, idempotencyKey),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: TERMINALS_KEY }),
  });
}

/**
 * The answer is the one-time code, a secret: the caller shows it from `data` and calls `reset()` when the dialog
 * closes, and `gcTime: 0` drops the finished mutation from the cache at once, so the code lives nowhere after that.
 */
export function useIssueEnrolmentCode() {
  const api = useApi();
  return useMutation({
    mutationFn: ({ terminalId }: { terminalId: string; terminalName: string }) => issueEnrolmentCode(api, terminalId),
    gcTime: 0,
  });
}

export function useRevokeTerminal() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ terminalId, idempotencyKey }: { terminalId: string; terminalName: string; idempotencyKey: string }) =>
      revokeTerminal(api, terminalId, idempotencyKey),
    onSettled: () => queryClient.invalidateQueries({ queryKey: TERMINALS_KEY }),
  });
}
