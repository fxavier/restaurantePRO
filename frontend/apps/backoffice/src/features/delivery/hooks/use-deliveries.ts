import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { DeliveryView } from '../types';

export const DELIVERY_PAGE_SIZE = 50;

export type DeliveryFilter = { from: string; to: string; courierId?: string };
export type DeliveryPage = { deliveries: DeliveryView[]; totalElements: number };

/** One page of the store's deliveries whose order's business day is in [from, to], optionally of one courier. */
export function useDeliveries(storeId: string, filter: DeliveryFilter, page: number, enabled: boolean) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'deliveries', storeId, filter, page],
    queryFn: async (): Promise<DeliveryPage> => {
      const { data } = await api.GET('/api/v1/backoffice/deliveries', {
        params: { query: { storeId, ...filter, page, size: DELIVERY_PAGE_SIZE } },
      });
      return { deliveries: data?.content ?? [], totalElements: data?.totalElements ?? 0 };
    },
    placeholderData: keepPreviousData,
    enabled,
  });
}
