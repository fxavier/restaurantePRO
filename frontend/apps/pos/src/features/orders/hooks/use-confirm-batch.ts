import { ApiError, type components } from '@restaurantpro/api-client';
import type { MoneyValue } from '@restaurantpro/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { isUnknownOutcome, useIntent } from '../../../lib/use-intent';
import type { Order } from '../order';
import { orderKey } from './use-order';

type Schemas = components['schemas'];
type BatchView = Schemas['com.xavier.backend.orders.model.dto.BatchView'];
type ConfirmBatch = Schemas['com.xavier.backend.orders.model.dto.ConfirmBatchRequest'];

/** What the screen showed when "Pedir" was pressed: each draft's gross, to show what a price conflict changed. */
export type Shown = { gross: ReadonlyMap<string, MoneyValue | null> };

/**
 * `POST /api/v1/pos/orders/{id}/batches` ("Pedir"): one intent per press (FC §8), with the versions the screen shows.
 * Whatever the answer, the order is read again; a catalog or promotion conflict reads the catalog again too. Nothing is
 * shown as sent until the order read again says so. A stale `orderVersion` (`VERSION_CONFLICT`) is definitive: a key
 * that committed replays its round instead, so it is never resent.
 */
export function useConfirmBatch(
  orderId: string,
  {
    onSent,
    onRefused,
  }: {
    /** The round as the server confirmed it. */
    onSent?: (batch: BatchView | undefined) => void;
    /** A definitive refusal, with what the screen showed when "Pedir" was pressed. */
    onRefused?: (error: Error, shown: Shown) => void;
  } = {},
) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const refreshOrder = () => {
    void queryClient.invalidateQueries({ queryKey: orderKey(orderId) });
    void queryClient.invalidateQueries({ queryKey: ['pos', 'tables'] });
  };

  return useIntent<ConfirmBatch, BatchView | undefined, Shown>(
    async (body, key) =>
      (
        await api.POST('/api/v1/pos/orders/{id}/batches', {
          params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
          body,
        })
      ).data,
    {
      onSuccess(batch) {
        refreshOrder();
        onSent?.(batch);
      },
      onError(error, intent) {
        refreshOrder();
        if (isCatalogConflict(error)) void queryClient.invalidateQueries({ queryKey: ['pos', 'catalog'] });
        if (!isUnknownOutcome(error)) onRefused?.(error, intent.meta);
      },
      onDiscard: refreshOrder,
    },
  );
}

/**
 * The round request for the drafts the screen shows ("Pedir" on the order screen, "Enviar ronda N" on the table map),
 * with what was shown; none while the order has no catalog version (no draft to send).
 */
export function roundRequest(order: Order): [ConfirmBatch, Shown] | undefined {
  if (order.catalogVersion === null) return undefined;
  return [
    {
      orderVersion: order.version,
      catalogVersion: order.catalogVersion,
      promotionVersion: order.promotionVersion ?? undefined,
      lineIds: order.drafts.map((line) => line.id),
      // D104: the server's draftGross echoed as given (its decimal string, verbatim), so a round that would now cost
      // anything else is refused with PRICES_CHANGED (a draft changed on another terminal, a promotion window
      // closed). String() only matters for a number replayed from before decimals became strings (FE-F33).
      expectedGross: order.draftGross ? String(order.draftGross.amount) : undefined,
    },
    { gross: new Map(order.drafts.map((line) => [line.id, line.gross])) },
  ];
}

const PRICE_CONFLICTS =new Set(['PRICES_CHANGED', 'CATALOG_CHANGED', 'PROMOTIONS_CHANGED']);

/** The prices the screen showed are no longer the ones a round would get (spec §8.4, D104). */
export function isCatalogConflict(error: unknown): boolean {
  return error instanceof ApiError && PRICE_CONFLICTS.has(error.code);
}
