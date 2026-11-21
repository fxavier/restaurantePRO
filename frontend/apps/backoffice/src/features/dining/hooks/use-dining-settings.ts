import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { DiningSettingsRequest, DiningSettingsView, ReservationView } from '../types';

const SETTINGS_KEY = ['backoffice', 'dining', 'settings'] as const;

/** The store's reservation window and no-show grace (P47); `version` is null while the store uses the defaults. */
export function useDiningSettings(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...SETTINGS_KEY, storeId],
    queryFn: async () =>
      (await api.GET('/api/v1/backoffice/dining/settings/{storeId}', { params: { path: { storeId } } })).data as DiningSettingsView,
  });
}

/** No Idempotency-Key (the contract takes none); the settings are read again whatever the answer. */
export function useSaveDiningSettings(storeId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DiningSettingsRequest) =>
      api
        .PUT('/api/v1/backoffice/dining/settings/{storeId}', { params: { path: { storeId } }, body })
        .then(({ data }) => data as DiningSettingsView),
    onSettled: () => queryClient.invalidateQueries({ queryKey: [...SETTINGS_KEY, storeId] }),
  });
}

/** A store's reservations on one day (`from` = `to`, inclusive; RESERVATION_VIEW), read-only here. */
export function useDayReservations(storeId: string, date: string) {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'reservations', storeId, date],
    queryFn: async () =>
      (await api.GET('/api/v1/backoffice/reservations', { params: { query: { storeId, from: date, to: date } } }))
        .data as ReservationView[],
    enabled: date !== '',
  });
}
