import type { ApiClient } from '@restaurantpro/api-client';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type {
  RestaurantTableCreateRequest,
  RestaurantTableSummary,
  RestaurantTableUpdateRequest,
  RoomAreaCreateRequest,
  RoomAreaSummary,
  RoomAreaUpdateRequest,
} from '../types';
import { AREAS_KEY, TABLES_KEY } from './use-floor';

/**
 * Areas and tables (§5): no Idempotency-Key (the contract takes none, FC §8), mutations never retry, and whatever the
 * answer the floor is read again, so the server's values are the ones shown.
 */
function useFloorMutation<Variables, Result>(send: (api: ApiClient, variables: Variables) => Promise<Result>) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (variables: Variables) => send(api, variables),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: AREAS_KEY }),
        queryClient.invalidateQueries({ queryKey: TABLES_KEY }),
      ]),
  });
}

type Edit<Body> = { id: string; body: Body };

export function useCreateArea() {
  return useFloorMutation((api, body: RoomAreaCreateRequest) =>
    api.POST('/api/v1/backoffice/areas', { body }).then(({ data }) => data as RoomAreaSummary),
  );
}

export function useUpdateArea() {
  return useFloorMutation((api, { id, body }: Edit<RoomAreaUpdateRequest>) =>
    api.PUT('/api/v1/backoffice/areas/{id}', { params: { path: { id } }, body }).then(({ data }) => data as RoomAreaSummary),
  );
}

export function useCreateTable() {
  return useFloorMutation((api, body: RestaurantTableCreateRequest) =>
    api.POST('/api/v1/backoffice/tables', { body }).then(({ data }) => data as RestaurantTableSummary),
  );
}

export function useUpdateTable() {
  return useFloorMutation((api, { id, body }: Edit<RestaurantTableUpdateRequest>) =>
    api.PUT('/api/v1/backoffice/tables/{id}', { params: { path: { id } }, body }).then(({ data }) => data as RestaurantTableSummary),
  );
}

/** The area or table as the server has it now, for an edit that met a newer version (409 VERSION_CONFLICT). */
export function useReadFloorItem() {
  const api = useApi();
  const byId = (id: string) => ({ params: { path: { id } } });
  return {
    area: (id: string) => api.GET('/api/v1/backoffice/areas/{id}', byId(id)).then(({ data }) => data as RoomAreaSummary),
    table: (id: string) => api.GET('/api/v1/backoffice/tables/{id}', byId(id)).then(({ data }) => data as RestaurantTableSummary),
  };
}
