import { ApiError, type components } from '@restaurantpro/api-client';
import type { MoneyValue } from '@restaurantpro/ui';
import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { isInProgress, isUnknownOutcome, useIntent } from '../../../lib/use-intent';
import { orderKey } from './use-order';

type Schemas = components['schemas'];
type ConfirmBatchRequest = Schemas['com.xavier.backend.orders.model.dto.ConfirmBatchRequest'];
type BatchView = Schemas['com.xavier.backend.orders.model.dto.BatchView'];

/** What the screen showed when "Pedir" was pressed: each draft's gross, to show what a price conflict changed. */
export type Shown = { gross: ReadonlyMap<string, MoneyValue | undefined> };

/**
 * The backend answers both a stale `orderVersion` and the same key still running with an uncoded 409
 * (gap-in-progress-code). The order read again tells them apart: at another version than the one sent, the round was
 * refused as stale (or another request changed it); at the same version, nothing changed, so the key is still running.
 */
export class StaleOrderError extends Error {
  constructor(options?: ErrorOptions) {
    super('The order changed', options);
    this.name = 'StaleOrderError';
  }
}

/**
 * `POST /api/v1/pos/orders/{id}/batches` ("Pedir"): one intent per press (FC §8), with the versions the screen shows.
 * Whatever the answer, the order is read again; a catalog or promotion conflict reads the catalog again too. Nothing is
 * shown as sent until the order read again says so.
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

  async function versionNow(): Promise<number | undefined> {
    try {
      return (await api.GET('/api/v1/pos/orders/{id}', { params: { path: { id: orderId } } })).data?.version;
    } catch {
      return undefined; // unreadable: assume the key is still running, the safe reading
    }
  }

  return useIntent<ConfirmBatchRequest, BatchView | undefined, Shown>(
    async (body, key) => {
      try {
        return (
          await api.POST('/api/v1/pos/orders/{id}/batches', {
            params: { path: { id: orderId }, header: { 'Idempotency-Key': key } },
            body,
          })
        ).data;
      } catch (error) {
        if (isInProgress(error)) {
          const version = await versionNow();
          if (version !== undefined && version !== body.orderVersion) throw new StaleOrderError({ cause: error });
        }
        throw error;
      }
    },
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

const PRICE_CONFLICTS = new Set(['PRICES_CHANGED', 'CATALOG_CHANGED', 'PROMOTIONS_CHANGED']);

/** The prices the screen showed are no longer the ones a round would get (spec §8.4, D104). */
export function isCatalogConflict(error: unknown): boolean {
  return error instanceof ApiError && PRICE_CONFLICTS.has(error.code);
}
