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

export type CloseSessionRequest = Schemas['com.xavier.backend.settlement.model.dto.CloseSessionRequest'];

/** `POST /api/v1/pos/cash/sessions/{id}/close`: the operator's own session, closed with its blind count. */
export function useCloseSession(sessionId: string) {
  const { api } = useServices();
  return useCashIntent(
    async (body: CloseSessionRequest, key: string) =>
      (
        await api.POST('/api/v1/pos/cash/sessions/{id}/close', {
          params: { path: { id: sessionId }, header: { 'Idempotency-Key': key } },
          body,
        })
      ).data,
  );
}

export type ClosingView = Schemas['com.xavier.backend.cash.model.dto.ClosingView'];
export type CreateClosingRequest = Schemas['com.xavier.backend.cash.model.dto.CreateClosingRequest'];
export type JustifyClosingRequest = Schemas['com.xavier.backend.cash.model.dto.JustifyClosingRequest'];

/**
 * `POST /api/v1/pos/cash/closings`: drafts the register's closing for the day (§8.3) from the server's expected and
 * counted amounts. The answer goes to `onClosing`: the POS has no read of a closing by id.
 */
export function useCreateClosing(onClosing: (closing: ClosingView) => void) {
  const { api } = useServices();
  return useCashIntent(
    async (body: CreateClosingRequest, key: string) =>
      (await api.POST('/api/v1/pos/cash/closings', { params: { header: { 'Idempotency-Key': key } }, body })).data,
    (closing) => closing && onClosing(closing),
  );
}

/**
 * A drafted closing's next steps (§8.3: DRAFT → SUBMITTED): `justify` explains its differences, `submit` hands it to
 * the manager. Each answer is the closing as the server now holds it, passed to `onClosing`.
 */
export function useClosingSteps(closingId: string, onClosing: (closing: ClosingView) => void) {
  const { api } = useServices();
  const done = (closing: ClosingView | undefined) => closing && onClosing(closing);
  const path = { id: closingId };
  const justify = useCashIntent(
    async (body: JustifyClosingRequest, key: string) =>
      (await api.PUT('/api/v1/pos/cash/closings/{id}', { params: { path, header: { 'Idempotency-Key': key } }, body }))
        .data,
    done,
  );
  const submit = useCashIntent(
    async (_: undefined, key: string) =>
      (await api.POST('/api/v1/pos/cash/closings/{id}/submit', { params: { path, header: { 'Idempotency-Key': key } } }))
        .data,
    done,
  );
  return { justify, submit };
}

/** `POST /api/v1/pos/drawer-kicks`: the manual "open drawer" (D158), a print job the printer runs. */
export function useDrawerKick(onSent: () => void) {
  const { api } = useServices();
  return useIntent(
    async (_: undefined, key: string) =>
      (await api.POST('/api/v1/pos/drawer-kicks', { params: { header: { 'Idempotency-Key': key } }, body: {} })).data,
    { onSuccess: onSent },
  );
}

/** `POST /api/v1/pos/cash/sessions`: opens the employee's session on a register with its float. */
export function useOpenSession() {
  const { api } = useServices();
  return useCashIntent(
    async (body: OpenSessionRequest, key: string) =>
      (await api.POST('/api/v1/pos/cash/sessions', { params: { header: { 'Idempotency-Key': key } }, body })).data,
  );
}
