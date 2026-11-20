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
function useInvalidateFloor() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: AREAS_KEY }),
      queryClient.invalidateQueries({ queryKey: TABLES_KEY }),
    ]);
}

export function useCreateArea() {
  const api = useApi();
  const onSettled = useInvalidateFloor();
  return useMutation({
    mutationFn: (body: RoomAreaCreateRequest) =>
      api.POST('/api/v1/backoffice/areas', { body }).then(({ data }) => data as RoomAreaSummary),
    onSettled,
  });
}

export function useUpdateArea() {
  const api = useApi();
  const onSettled = useInvalidateFloor();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: RoomAreaUpdateRequest }) =>
      api.PUT('/api/v1/backoffice/areas/{id}', { params: { path: { id } }, body }).then(({ data }) => data as RoomAreaSummary),
    onSettled,
  });
}

export function useCreateTable() {
  const api = useApi();
  const onSettled = useInvalidateFloor();
  return useMutation({
    mutationFn: (body: RestaurantTableCreateRequest) =>
      api.POST('/api/v1/backoffice/tables', { body }).then(({ data }) => data as RestaurantTableSummary),
    onSettled,
  });
}

export function useUpdateTable() {
  const api = useApi();
  const onSettled = useInvalidateFloor();
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: RestaurantTableUpdateRequest }) =>
      api
        .PUT('/api/v1/backoffice/tables/{id}', { params: { path: { id } }, body })
        .then(({ data }) => data as RestaurantTableSummary),
    onSettled,
  });
}

/** The area or table as the server has it now, for an edit that met a newer version (409 VERSION_CONFLICT). */
export function useReadFloorItem() {
  const api = useApi();
  return {
    area: (id: string) =>
      api.GET('/api/v1/backoffice/areas/{id}', { params: { path: { id } } }).then(({ data }) => data as RoomAreaSummary),
    table: (id: string) =>
      api.GET('/api/v1/backoffice/tables/{id}', { params: { path: { id } } }).then(({ data }) => data as RestaurantTableSummary),
  };
}
