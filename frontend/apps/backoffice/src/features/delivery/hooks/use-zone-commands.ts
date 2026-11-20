import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import type { ZoneCreateRequest, ZoneUpdateRequest, ZoneView } from '../types';
import { ZONES_KEY } from './use-zones';

const ZONES = '/api/v1/backoffice/delivery-zones';

/**
 * Mutations never retry (FC §8): "Tentar novamente" resends `variables`, the same request under the same
 * Idempotency-Key, which the caller makes when the user starts the intent. A 409 IDEMPOTENCY_IN_PROGRESS waits and is
 * resent with that key.
 */
export function useCreateZone() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: ZoneCreateRequest; idempotencyKey: string }) =>
      sendWaitingWhileInProgress(() =>
        api.POST(ZONES, { body, params: { header: { 'Idempotency-Key': idempotencyKey } } }),
      ).then(({ data }) => data as ZoneView),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ZONES_KEY }),
  });
}

/** The update carries no key (the contract has none): its `version` makes a repeat a 409, never a second change. */
export function useUpdateZone() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ zoneId, body }: { zoneId: string; body: ZoneUpdateRequest }) =>
      api.PUT(`${ZONES}/{id}`, { body, params: { path: { id: zoneId } } }).then(({ data }) => data as ZoneView),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ZONES_KEY }),
  });
}
