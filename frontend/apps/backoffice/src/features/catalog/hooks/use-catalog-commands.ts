import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { FamilySummary, ItemCreateRequest, ItemSummary, ItemUpdateRequest } from '../types';
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
