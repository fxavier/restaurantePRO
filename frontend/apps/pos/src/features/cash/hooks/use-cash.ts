import { ApiError, type components } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';

type Schemas = components['schemas'];
export type SessionDetails = Schemas['com.xavier.backend.cash.model.dto.SessionDetails'];
export type RegisterSummary = Schemas['com.xavier.backend.cash.model.dto.RegisterSummary'];
export type CurrentDay = Schemas['com.xavier.backend.cash.model.dto.CurrentDayView'];

/** Every cash query starts with this key, so one invalidation reads the whole screen again after a command. */
export const CASH_KEY = ['pos', 'cash'] as const;

/**
 * `GET /api/v1/pos/cash/sessions/current`: the employee's own open session, or `null` when there is none (404).
 */
export function useCurrentSession(enabled: boolean) {
  const { api } = useServices();
  return useQuery({
    enabled,
    queryKey: [...CASH_KEY, 'sessions', 'current'],
    queryFn: async ({ signal }) => {
      try {
        return (await api.GET('/api/v1/pos/cash/sessions/current', { signal })).data ?? null;
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null;
        throw error;
      }
    },
  });
}

/** `GET /api/v1/pos/cash/registers`: the store's registers (a store has a handful; one page holds them all). */
export function useRegisters(enabled: boolean) {
  const { api } = useServices();
  return useQuery({
    enabled,
    queryKey: [...CASH_KEY, 'registers'],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/cash/registers', { params: { query: { size: 100 } }, signal })).data?.content ?? [],
  });
}

/** `GET /api/v1/pos/cash/days/current`: the store's business day now. */
export function useCurrentDay(enabled: boolean) {
  const { api } = useServices();
  return useQuery({
    enabled,
    queryKey: [...CASH_KEY, 'days', 'current'],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/cash/days/current', { signal })).data ?? null,
  });
}
