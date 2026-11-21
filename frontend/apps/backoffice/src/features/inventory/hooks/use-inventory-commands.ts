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
  RecipeRequest,
  RecipeVersionRequest,
  SupplierRequest,
  TransferView,
} from '../types';
import { INVENTORY_KEY } from './use-inventory-reads';

const BASE = '/api/v1/backoffice/inventory';
const key = (idempotencyKey: string) => ({ 'Idempotency-Key': idempotencyKey });

/**
 * One inventory command: never retried by the client (FC §8; "Tentar novamente" resends `variables`, same key), and
 * whatever its answer, the area is read again, so a 409 shows what changed and a success shows the server's values.
 */
function useCommand<Variables>(send: (api: ApiClient, variables: Variables) => Promise<unknown>) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: Variables) => sendWaitingWhileInProgress(() => send(api, variables)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: INVENTORY_KEY }),
  });
}

export type MovementKind = 'waste' | 'manual-adjustments';

export function useRecordMovement() {
  return useCommand((api, { kind, body, idempotencyKey }: Intent<ManualMovementRequest> & { kind: MovementKind }) =>
    api.POST(`${BASE}/movements/${kind}`, { body, params: { header: key(idempotencyKey) } }),
  );
}

export function useCreateSupplier() {
  return useCommand((api, { body, idempotencyKey }: Intent<SupplierRequest>) =>
    api.POST(`${BASE}/suppliers`, { body, params: { header: key(idempotencyKey) } }),
  );
}

export function useCreatePurchaseOrder() {
  return useCommand((api, { body, idempotencyKey }: Intent<PurchaseOrderRequest>) =>
    api.POST(`${BASE}/purchase-orders`, { body, params: { header: key(idempotencyKey) } }),
  );
}

export type OrderAction = 'submit' | 'close' | 'cancel';
export type RowCommand<Action extends string> = { id: string; action: Action; label: string; idempotencyKey: string };

export function usePurchaseOrderAction() {
  return useCommand((api, { id, action, idempotencyKey }: RowCommand<OrderAction>) =>
    api.POST(`${BASE}/purchase-orders/{id}/${action}`, { params: { path: { id }, header: key(idempotencyKey) } }),
  );
}

export function useCreateReceipt() {
  return useCommand((api, { body, idempotencyKey }: Intent<ReceiptRequest>) =>
    api.POST(`${BASE}/receipts`, { body, params: { header: key(idempotencyKey) } }),
  );
}

export type ReceiptAction = 'confirm' | 'cancel';

export function useReceiptAction() {
  return useCommand((api, { id, action, idempotencyKey }: RowCommand<ReceiptAction>) =>
    api.POST(`${BASE}/receipts/{id}/${action}`, { params: { path: { id }, header: key(idempotencyKey) } }),
  );
}

export function useCreateRecipe() {
  return useCommand((api, { body, idempotencyKey }: Intent<RecipeRequest>) =>
    api.POST(`${BASE}/recipes`, { body, params: { header: key(idempotencyKey) } }),
  );
}

export function useCreateRecipeVersion() {
  return useCommand((api, { recipeId, body, idempotencyKey }: Intent<RecipeVersionRequest> & { recipeId: string }) =>
    api.POST(`${BASE}/recipes/{id}/versions`, { body, params: { path: { id: recipeId }, header: key(idempotencyKey) } }),
  );
}

export function useActivateRecipeVersion() {
  return useCommand(
    (api, { recipeId, versionNo, idempotencyKey }: { recipeId: string; versionNo: number; idempotencyKey: string }) =>
      api.POST(`${BASE}/recipes/{id}/versions/{versionNo}/activate`, {
        body: {},
        params: { path: { id: recipeId, versionNo }, header: key(idempotencyKey) },
      }),
  );
}

export function useCreateCount() {
  const api = useApi();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: Intent<CountRequest>) =>
      sendWaitingWhileInProgress(() => api.POST(`${BASE}/counts`, { body, params: { header: key(idempotencyKey) } })).then(
        ({ data }) => data!,
      ),
  });
}

export type CountAction = 'start' | 'submit' | 'approve' | 'cancel';

export function useCountAction() {
  return useCommand((api, { id, action, idempotencyKey }: RowCommand<CountAction>) =>
    api.POST(`${BASE}/counts/{id}/${action}`, { params: { path: { id }, header: key(idempotencyKey) } }),
  );
}

/** What was counted of one item; repeating it records the same quantity again (a PUT, no key). */
export function useRecordCountLine() {
  return useCommand((api, { countId, stockItemId, countedQuantity }: { countId: string; stockItemId: string; countedQuantity: string }) =>
    api.PUT(`${BASE}/counts/{id}/lines/{stockItemId}`, {
      body: { countedQuantity },
      params: { path: { id: countId, stockItemId } },
    }),
  );
}

export type TransferAction = 'ship' | 'receive' | 'cancel';

/** Receiving takes what was shipped, line by line, as the server reported it (the shortfall stays a server concern). */
export function useTransferAction() {
  return useCommand((api, { id, action, idempotencyKey, transfer }: RowCommand<TransferAction> & { transfer: TransferView }) => {
    const params = { path: { id }, header: key(idempotencyKey) };
    if (action !== 'receive') return api.POST(`${BASE}/transfers/{id}/${action}`, { params });
    const lines = (transfer.lines ?? []).map((line) => ({ stockItemId: line.stockItemId!, quantityReceived: line.quantityShipped! }));
    return api.POST(`${BASE}/transfers/{id}/receive`, { params, body: { lines } });
  });
}
