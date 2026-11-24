import { useQuery } from '@tanstack/react-query';
import { useEmployee } from '../../../lib/auth/use-employee';
import { useServices } from '../../../lib/services';
import { maySeePaymentAlerts, maySeeProductionAlerts, toProductionAlerts, toSettlementAlerts } from '../alerts';

/** A `PRINT_JOBS` or `PRODUCTION_TICKETS` change invalidates it (lib/live). */
export const productionAlertsKey = ['pos', 'production-alerts'] as const;

/** A `PAYMENT_ATTEMPT` change invalidates it (lib/live). */
export const settlementAlertsKey = ['pos', 'settlement-alerts'] as const;

/**
 * `GET /api/v1/pos/production/alerts`, only for a session that may see it. A round grows late with time alone, which no
 * change announces, so it is also read every 15 s (as the deliveries are). Keyed by the employee: the next one never
 * sees an answer read for another.
 */
export function useProductionAlerts() {
  const { api } = useServices();
  const employee = useEmployee();
  return useQuery({
    queryKey: [...productionAlertsKey, employee?.employeeId],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/production/alerts', { signal })).data,
    select: toProductionAlerts,
    enabled: maySeeProductionAlerts(employee),
    refetchInterval: 15_000,
  });
}

/** `GET /api/v1/pos/settlement/alerts`, only for a session that may see it; every attempt change reads it again. */
export function useSettlementAlerts() {
  const { api } = useServices();
  const employee = useEmployee();
  return useQuery({
    queryKey: [...settlementAlertsKey, employee?.employeeId],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/settlement/alerts', { signal })).data,
    select: toSettlementAlerts,
    enabled: maySeePaymentAlerts(employee),
  });
}
