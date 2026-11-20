import type { components } from '@restaurantpro/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { CASH_KEY } from './use-cash';

type Schemas = components['schemas'];
export type OpenSessionRequest = Schemas['com.xavier.backend.cash.model.dto.OpenSessionRequest'];

/**
 * A cash command as one user intent (FC §8: one key per intent, the same key on "Tentar novamente", never retried on
 * its own). Whatever the answer, the cash screen is read again: the server's state is the one shown.
 */
function useCashIntent<Request, Result>(
  send: (request: Request, key: string) => Promise<Result>,
  onDone?: (result: Result) => void,
) {
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: CASH_KEY });
  return useIntent<Request, Result>(send, {
    onSuccess(result) {
      refresh();
      onDone?.(result);
    },
    onError: refresh,
    onDiscard: refresh,
  });
}

/** `POST /api/v1/pos/cash/sessions`: opens the employee's session on a register with its float. */
export function useOpenSession() {
  const { api } = useServices();
  return useCashIntent(
    async (body: OpenSessionRequest, key: string) =>
      (await api.POST('/api/v1/pos/cash/sessions', { params: { header: { 'Idempotency-Key': key } }, body })).data,
  );
}
