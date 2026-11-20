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

export type MovementRequest = Schemas['com.xavier.backend.cash.model.dto.ManualMovementRequest'];
export type MovementView = Schemas['com.xavier.backend.cash.MovementView'];
export type Approver = { code: string; pin: string };

/**
 * `POST /api/v1/pos/cash/movements`: a cash in, cash out or expense in the operator's session. An expense's approver
 * (P24) is read from `approver` when the request is sent and goes in headers only: the intent never holds the PIN.
 */
export function useRecordMovement(approver: () => Approver | undefined, onRecorded: (movement: MovementView) => void) {
  const { api } = useServices();
  return useCashIntent(async (body: MovementRequest, key: string) => {
    const approval = approver();
    const header = {
      'Idempotency-Key': key,
      ...(approval && { 'X-Approver-Code': approval.code, 'X-Approver-Pin': approval.pin }),
    };
    return (await api.POST('/api/v1/pos/cash/movements', { params: { header }, body })).data;
  }, (movement) => movement && onRecorded(movement));
}

/** `POST /api/v1/pos/cash/sessions`: opens the employee's session on a register with its float. */
export function useOpenSession() {
  const { api } = useServices();
  return useCashIntent(
    async (body: OpenSessionRequest, key: string) =>
      (await api.POST('/api/v1/pos/cash/sessions', { params: { header: { 'Idempotency-Key': key } }, body })).data,
  );
}
