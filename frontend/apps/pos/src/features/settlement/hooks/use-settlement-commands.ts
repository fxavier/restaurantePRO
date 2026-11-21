import type { components } from '@restaurantpro/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { settlementKey } from './use-settlement';

/**
 * A settlement command: one intent per press (FC §8), and the bill read again whatever the answer, so the screen shows
 * the server's parts, payments and states, never a guess.
 */
function useSettlementIntent<Request, Result>(
  orderId: string,
  send: (request: Request, key: string) => Promise<Result>,
  onDone?: (result: Result) => void,
) {
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: settlementKey(orderId) });
  return useIntent<Request, Result>(send, {
    onSuccess(result) {
      refresh();
      onDone?.(result);
    },
    onError: refresh,
    onDiscard: refresh,
  });
}

export type Attempt = components['schemas']['com.xavier.backend.settlement.model.dto.AttemptView'];
export type ChargeRequest = components['schemas']['com.xavier.backend.settlement.model.dto.CreateAttemptRequest'];

/**
 * `POST /api/v1/pos/payment-attempts`: one intent per "Cobrar" (§7.4). Its answer is the attempt (cash: SUCCEEDED with
 * its change; a card terminal: PENDING until the operator confirms).
 */
export function useCharge(orderId: string, onCharged: (attempt: Attempt | undefined) => void) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async (body: ChargeRequest, key) =>
      (await api.POST('/api/v1/pos/payment-attempts', { params: { header: { 'Idempotency-Key': key } }, body })).data,
    onCharged,
  );
}

export type FiscalDocument = components['schemas']['com.xavier.backend.fiscal.model.dto.DocumentView'];
export type Buyer = components['schemas']['com.xavier.backend.fiscal.model.dto.Buyer'];

/** `POST /api/v1/pos/orders/{id}/documents`: the order's document, issued for the buyer given (or none). */
export function useIssueOrderDocument(orderId: string, onIssued: (document: FiscalDocument | undefined) => void) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async (buyer: Buyer | undefined, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/documents', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body: buyer ? { buyer } : {},
        })
      ).data,
    onIssued,
  );
}

/**
 * `POST /api/v1/pos/partitions/{id}/documents`: a part by items invoiced on its own (D70); the order is not frozen, so
 * the table goes on ordering.
 */
export function useIssuePartitionDocument(orderId: string, onIssued: (document: FiscalDocument | undefined) => void) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async ({ partitionId, buyer }: { partitionId: string; buyer: Buyer | undefined }, key) =>
      (
        await api.POST('/api/v1/pos/partitions/{id}/documents', {
          params: { path: { id: partitionId }, header: { 'Idempotency-Key': key } },
          body: buyer ? { buyer } : {},
        })
      ).data,
    onIssued,
  );
}

export type Reprint = components['schemas']['com.xavier.backend.fiscal.model.dto.ReprintView'];

/** `POST /api/v1/pos/documents/{id}/reprints`: a copy ("2ª via"), never a new document, payment or movement. */
export function useReprint(onPrinted: (reprint: Reprint | undefined) => void) {
  const { api } = useServices();
  return useIntent<string, Reprint | undefined>(
    async (documentId, key) =>
      (
        await api.POST('/api/v1/pos/documents/{id}/reprints', {
          params: { path: { id: documentId }, header: { 'Idempotency-Key': key } },
        })
      ).data,
    { onSuccess: onPrinted },
  );
}

/** `POST /api/v1/pos/orders/{id}/close` at the bill's version: the order and its table are closed (D55, D70). */
export function useCloseOrder(orderId: string, onClosed: () => void) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async (orderVersion: number, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/close', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body: { orderVersion },
        })
      ).data,
    onClosed,
  );
}

/**
 * `POST /api/v1/pos/payment-attempts/{id}/resolve` without an outcome: the provider is asked for an UNKNOWN attempt's
 * result (D81). Reclassifying it by hand needs an approver, which the POS does not offer here.
 */
export function useResolveAttempt(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async (attemptId: string, key) =>
    (
      await api.POST('/api/v1/pos/payment-attempts/{id}/resolve', {
        params: { path: { id: attemptId }, header: { 'Idempotency-Key': key } },
        body: {},
      })
    ).data,
  );
}

/** `POST /api/v1/pos/payment-attempts/{id}/confirm`: the operator saw the card terminal approve it (D50 §7.5). */
export function useConfirmAttempt(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async ({ attemptId, authorizationCode }: { attemptId: string; authorizationCode?: string }, key) =>
    (
      await api.POST('/api/v1/pos/payment-attempts/{id}/confirm', {
        params: { path: { id: attemptId }, header: { 'Idempotency-Key': key } },
        body: { authorizationCode },
      })
    ).data,
  );
}

/** `POST /api/v1/pos/payment-attempts/{id}/cancel`: the card terminal did not approve it. */
export function useCancelAttempt(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async ({ attemptId, reason }: { attemptId: string; reason: string }, key) =>
    (
      await api.POST('/api/v1/pos/payment-attempts/{id}/cancel', {
        params: { path: { id: attemptId }, header: { 'Idempotency-Key': key } },
        body: { reason },
      })
    ).data,
  );
}

/** `POST /api/v1/pos/partitions/{id}/cancel` at its version: refused once it has a payment or a document (D67). */
export function useCancelPartition(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async ({ partitionId, version }: { partitionId: string; version: number }, key) =>
    (
      await api.POST('/api/v1/pos/partitions/{id}/cancel', {
        params: { path: { id: partitionId }, header: { 'Idempotency-Key': key } },
        body: { version, reason: 'Divisão refeita no ponto de venda' },
      })
    ).data,
  );
}

/** `POST /api/v1/pos/orders/{id}/partitions` with kind `ITEMS`: quantities of lines as one part (D67). */
export function useSplitByItems(orderId: string, onSplit: () => void) {
  const { api } = useServices();
  return useSettlementIntent(
    orderId,
    async (body: { orderVersion: number; lines: { lineId: string; quantity: string }[] }, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/partitions', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body: { orderVersion: body.orderVersion, kind: 'ITEMS', lines: body.lines },
        })
      ).data,
    onSplit,
  );
}

/** `POST /api/v1/pos/orders/{id}/partitions/equal`: the residual in `parts` equal parts, at the bill's version. */
export function useSplitEqually(orderId: string) {
  const { api } = useServices();
  return useSettlementIntent(orderId, async (body: { orderVersion: number; parts: number }, key) =>
    (
      await api.POST('/api/v1/pos/orders/{id}/partitions/equal', {
        params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
        body,
      })
    ).data,
  );
}
