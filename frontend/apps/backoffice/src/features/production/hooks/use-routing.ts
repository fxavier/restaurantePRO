import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { DestinationRouteView, TerminalPrinterView } from '../types';

export const ROUTES_KEY = ['backoffice', 'production-routes'] as const;
export const TERMINAL_PRINTERS_KEY = ['backoffice', 'terminal-printers'] as const;

/** The store's destination → printer table (CONTEXT: DestinationRoute). */
export function useRoutes(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...ROUTES_KEY, storeId],
    queryFn: async (): Promise<DestinationRouteView[]> =>
      (await api.GET('/api/v1/backoffice/production-routes', { params: { query: { storeId } } })).data?.routes ?? [],
  });
}

/** The store's terminal → printer table for subtotals and documents (CONTEXT: TerminalPrinter). */
export function useTerminalPrinters(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...TERMINAL_PRINTERS_KEY, storeId],
    queryFn: async (): Promise<TerminalPrinterView[]> =>
      (await api.GET('/api/v1/backoffice/terminal-printers', { params: { query: { storeId } } })).data?.assignments ?? [],
  });
}
