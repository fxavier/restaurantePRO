import type { components } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

type RoundKitchenView = components['schemas']['com.xavier.backend.production.model.dto.RoundKitchenView'];

/** Where a round is in the kitchen (ADR 0044): that of its least advanced station, as the server says. */
export type KitchenState = RoundKitchenView['status'];

function byRound(views: RoundKitchenView[] | undefined): ReadonlyMap<string, KitchenState> {
  return new Map((views ?? []).map((view) => [view.batchId, view.status]));
}

/**
 * The kitchen state of each round of an order (`GET /api/v1/pos/production-tickets/rounds?orderId=`), one read for the
 * order. Under the production tickets' key, so a `PRODUCTION_TICKETS` change (a round queued or moved on the kitchen
 * display) reads it again.
 */
export function useRoundKitchen(orderId: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'production-tickets', 'rounds', orderId],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/production-tickets/rounds', { params: { query: { orderId } }, signal })).data,
    select: byRound,
  });
}
