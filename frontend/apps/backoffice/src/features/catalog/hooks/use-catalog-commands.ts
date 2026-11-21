import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { FamilySummary, ItemCreateRequest, ItemSummary, ItemUpdateRequest, PriceListSummary } from '../types';
import { publishRelease, type Publication } from '../release-commands';
import { CATALOG_KEY } from './use-catalog-queries';

type FamilyBody = { code: string; name: string; parentId?: string };

/**
 * Create (no `id`) or update a family. The contract takes no Idempotency-Key here; a resend after no answer may meet
 * the first one's code, which comes back as CATALOG_CODE_TAKEN. Mutations never retry by themselves (FC §8).
 */
export function useSaveFamily() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, version, body }: { id?: string; version?: number; body: FamilyBody }): Promise<FamilySummary> => {
      const { data } = id
        ? await api.PUT('/api/v1/backoffice/catalog/families/{id}', { params: { path: { id } }, body: { ...body, version: version ?? 0 } })
        : await api.POST('/api/v1/backoffice/catalog/families', { body });
      return data as FamilySummary;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'families'] }),
  });
}

/** Adds a variant to an item (no Idempotency-Key in the contract; a resend meets CATALOG_CODE_TAKEN). */
export function useAddVariant(itemId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: { code: string; name: string }) =>
      (await api.POST('/api/v1/backoffice/catalog/items/{id}/variants', { params: { path: { id: itemId } }, body })).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'items', itemId, 'variants'] }),
  });
}

/** Adds a barcode to an item, or to one of its variants. */
export function useAddBarcode(itemId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: { code: string; itemId: string; variantId?: string }) =>
      (await api.POST('/api/v1/backoffice/catalog/barcodes', { body })).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'items', itemId, 'barcodes'] }),
  });
}

/** A new price list of the store; its currency is the store's, set by the server. */
export function useCreatePriceList(storeId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (name: string): Promise<PriceListSummary> =>
      (await api.POST('/api/v1/backoffice/catalog/price-lists', { body: { name, storeId } })).data as PriceListSummary,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'price-lists', storeId] }),
  });
}

type PriceSave =
  | { priceId?: undefined; body: { itemId: string; variantId?: string; amount: string } }
  | { priceId: string; body: { amount: string; version: number } };

/** A new price in the list, or a changed amount under the version it was read at; the amount is a decimal string. */
export function useSavePrice(priceListId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (save: PriceSave) => {
      const path = { id: priceListId };
      const { data } =
        save.priceId === undefined
          ? await api.POST('/api/v1/backoffice/catalog/price-lists/{id}/prices', { params: { path }, body: save.body })
          : await api.PUT('/api/v1/backoffice/catalog/price-lists/{id}/prices/{priceId}', {
              params: { path: { ...path, priceId: save.priceId } },
              body: save.body,
            });
      return data;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'price-lists', 'prices', priceListId] }),
  });
}

/** The store's release commands; each refreshes the list when it ends, so a 409 shows the state that won. */
function useReleaseMutation<Variables>(storeId: string, send: (variables: Variables) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: send,
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'releases', storeId] }),
  });
}

/** A new DRAFT release of the store (no Idempotency-Key in the contract). */
export function useCreateRelease(storeId: string) {
  const api = useApi();
  return useReleaseMutation(storeId, async () => (await api.POST('/api/v1/backoffice/catalog/releases', { body: { storeId } })).data);
}

/** Validates the store's working copy into this release (DRAFT or VALIDATED → VALIDATED); 422 lists the problems. */
export function useValidateRelease(storeId: string) {
  const api = useApi();
  return useReleaseMutation(storeId, async (releaseId: string) =>
    (await api.POST('/api/v1/backoffice/catalog/releases/{id}/validate', { params: { path: { id: releaseId } } })).data,
  );
}

/** Publishes a VALIDATED release under the intent's Idempotency-Key (FC §8). */
export function usePublishRelease(storeId: string) {
  const api = useApi();
  return useReleaseMutation(storeId, (publication: Publication) => publishRelease(api, publication));
}

type ItemSave =
  | { id?: undefined; body: ItemCreateRequest }
  | { id: string; body: ItemUpdateRequest };

/** Create or update an item (no Idempotency-Key in the contract; a resend meets CATALOG_CODE_TAKEN). */
export function useSaveItem() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (save: ItemSave): Promise<ItemSummary> => {
      const { data } =
        save.id === undefined
          ? await api.POST('/api/v1/backoffice/catalog/items', { body: save.body })
          : await api.PUT('/api/v1/backoffice/catalog/items/{id}', { params: { path: { id: save.id } }, body: save.body });
      return data as ItemSummary;
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CATALOG_KEY, 'items'] }),
  });
}
