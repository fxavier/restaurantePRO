import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { StoreCreateRequest, StoreUpdateRequest } from '../store-types';
import type { StoreSummary } from '../types';

export const STORES_KEY = ['backoffice', 'stores'] as const;

/**
 * The contract takes no Idempotency-Key on these (FC §8: a key only where it is required), and mutations never retry.
 * Whatever the answer, the list is read again: the server's values are the ones shown.
 */
export function useCreateStore() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: StoreCreateRequest) =>
      api.POST('/api/v1/backoffice/stores', { body }).then(({ data }) => data as StoreSummary),
    onSettled: () => queryClient.invalidateQueries({ queryKey: STORES_KEY }),
  });
}

export function useUpdateStore() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: StoreUpdateRequest }) =>
      api.PUT('/api/v1/backoffice/stores/{id}', { params: { path: { id } }, body }).then(({ data }) => data as StoreSummary),
    onSettled: () => queryClient.invalidateQueries({ queryKey: STORES_KEY }),
  });
}

/** The store as the server has it now, for an edit that met a newer version (409 VERSION_CONFLICT). */
export function useReadStore() {
  const api = useApi();
  return (id: string) =>
    api.GET('/api/v1/backoffice/stores/{id}', { params: { path: { id } } }).then(({ data }) => data as StoreSummary);
}
