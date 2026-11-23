import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import type { Intent } from '../intent';
import type {
  CountRequest,
  ManualMovementRequest,
  PurchaseOrderRequest,
  ReceiptRequest,
  ReceiveTransferRequest,
  RecipeRequest,
  RecipeVersionRequest,
  SupplierRequest,
} from '../types';
import { INVENTORY_KEY } from './use-inventory-reads';

const BASE = '/api/v1/backoffice/inventory';
type Header = { 'Idempotency-Key': string };

/**
 * One inventory command: never retried by the client (FC §8; "Tentar novamente" resends `variables`, same key; a
 * 409 IDEMPOTENCY_IN_PROGRESS waits and resends), and whatever its answer, the area is read again, so a 409 shows
 * what changed and a success shows the server's values.
 */
function useCommand<Variables, Result>(send: (api: ApiClient, variables: Variables) => Promise<Result>) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: Variables) => sendWaitingWhileInProgress(() => send(api, variables)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: INVENTORY_KEY }),
  });
}

/** A command carrying a body, under the intent's Idempotency-Key. */
function useIntentCommand<Body, Extra = object>(send: (api: ApiClient, body: Body, header: Header, extra: Extra) => Promise<unknown>) {
  return useCommand((api, { body, idempotencyKey, ...extra }: Intent<Body> & Extra) =>
    send(api, body, { 'Idempotency-Key': idempotencyKey }, extra as Extra),
  );
}

export type RowCommand<Action extends string> = { id: string; action: Action; label: string; idempotencyKey: string };

/** A bodiless state change of one document (`POST …/{id}/<action>`), under its own key. */
function useRowAction<Action extends string, Extra = object>(
  send: (api: ApiClient, id: string, action: Action, header: Header, extra: Extra) => Promise<unknown>,
) {
  return useCommand((api, { id, action, idempotencyKey, ...extra }: RowCommand<Action> & Extra) =>
    send(api, id, action, { 'Idempotency-Key': idempotencyKey }, extra as Extra),
  );
}

export type MovementKind = 'waste' | 'manual-adjustments';

export const useRecordMovement = () =>
  useIntentCommand<ManualMovementRequest, { kind: MovementKind }>((api, body, header, { kind }) =>
    api.POST(`${BASE}/movements/${kind}`, { body, params: { header } }),
  );

export const useCreateSupplier = () =>
  useIntentCommand<SupplierRequest>((api, body, header) => api.POST(`${BASE}/suppliers`, { body, params: { header } }));

export const useCreatePurchaseOrder = () =>
  useIntentCommand<PurchaseOrderRequest>((api, body, header) => api.POST(`${BASE}/purchase-orders`, { body, params: { header } }));

export const useCreateReceipt = () =>
  useIntentCommand<ReceiptRequest>((api, body, header) => api.POST(`${BASE}/receipts`, { body, params: { header } }));

export const useCreateRecipe = () =>
  useIntentCommand<RecipeRequest>((api, body, header) => api.POST(`${BASE}/recipes`, { body, params: { header } }));

export const useCreateRecipeVersion = () =>
  useIntentCommand<RecipeVersionRequest, { recipeId: string }>((api, body, header, { recipeId }) =>
    api.POST(`${BASE}/recipes/{id}/versions`, { body, params: { path: { id: recipeId }, header } }),
  );

export type OrderAction = 'submit' | 'close' | 'cancel';

export const usePurchaseOrderAction = () =>
  useRowAction<OrderAction>((api, id, action, header) => api.POST(`${BASE}/purchase-orders/{id}/${action}`, { params: { path: { id }, header } }));

export const useReceiptAction = () =>
  useRowAction<'confirm' | 'cancel'>((api, id, action, header) => api.POST(`${BASE}/receipts/{id}/${action}`, { params: { path: { id }, header } }));

export const useCountAction = () =>
  useRowAction<'start' | 'submit' | 'approve' | 'cancel'>((api, id, action, header) =>
    api.POST(`${BASE}/counts/{id}/${action}`, { params: { path: { id }, header } }),
  );

export const useTransferAction = () =>
  useRowAction<'ship' | 'cancel'>((api, id, action, header) => api.POST(`${BASE}/transfers/{id}/${action}`, { params: { path: { id }, header } }));

/** What arrived of each line and, when short, why: the shortfall and its valuation stay a server concern (D120). */
export const useReceiveTransfer = () =>
  useIntentCommand<ReceiveTransferRequest, { transferId: string }>((api, body, header, { transferId }) =>
    api.POST(`${BASE}/transfers/{id}/receive`, { body, params: { path: { id: transferId }, header } }),
  );

export const useActivateRecipeVersion = () =>
  useCommand((api, { recipeId, versionNo, idempotencyKey }: { recipeId: string; versionNo: number; idempotencyKey: string }) =>
    api.POST(`${BASE}/recipes/{id}/versions/{versionNo}/activate`, {
      body: {},
      params: { path: { id: recipeId, versionNo }, header: { 'Idempotency-Key': idempotencyKey } },
    }),
  );

/** Creating a count answers the count, whose id the screen opens; nothing else to reread yet. */
export function useCreateCount() {
  const api = useApi();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: Intent<CountRequest>) =>
      sendWaitingWhileInProgress(() => api.POST(`${BASE}/counts`, { body, params: { header: { 'Idempotency-Key': idempotencyKey } } })).then(
        ({ data }) => data!,
      ),
  });
}

/** What was counted of one item; repeating it records the same quantity again (a PUT, no key). */
export const useRecordCountLine = () =>
  useCommand((api, { countId, stockItemId, countedQuantity }: { countId: string; stockItemId: string; countedQuantity: string }) =>
    api.PUT(`${BASE}/counts/{id}/lines/{stockItemId}`, { body: { countedQuantity }, params: { path: { id: countId, stockItemId } } }),
  );
