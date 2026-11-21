import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { DestinationRouteView, TerminalPrinterView } from '../types';

/**
 * Both tables are replaced whole (PUT, no key and no version: the contract asks neither). Whatever the answer, the
 * table is read again; a concurrent replacement answers ROUTES_CHANGED_CONCURRENTLY.
 */
export function useReplaceRoutes(storeId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (routes: DestinationRouteView[]) =>
      (await api.PUT('/api/v1/backoffice/production-routes', { body: { storeId, routes } })).data,
    onSettled: () => queryClient.invalidateQueries({ queryKey: ROUTES_KEY }),
  });
}

export function useReplaceTerminalPrinters(storeId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (assignments: TerminalPrinterView[]) =>
      (await api.PUT('/api/v1/backoffice/terminal-printers', { body: { storeId, assignments } })).data,
    onSettled: () => queryClient.invalidateQueries({ queryKey: TERMINAL_PRINTERS_KEY }),
  });
}

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
