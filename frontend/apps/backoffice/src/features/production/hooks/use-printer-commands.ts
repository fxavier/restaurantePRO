import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import type { PrinterCreateRequest, PrinterPresetView, PrinterUpdateRequest } from '../types';
import { PRINTERS_KEY } from './use-printers';

/** The printer profile presets (D154); they only fill the form. */
export function usePrinterPresets() {
  const api = useApi();
  return useQuery({
    queryKey: ['backoffice', 'printer-profiles'],
    queryFn: async (): Promise<PrinterPresetView[]> => (await api.GET('/api/v1/backoffice/printer-profiles')).data ?? [],
    staleTime: Infinity,
  });
}

/**
 * Printer configuration (FC §8: mutations never retry). The contract asks no Idempotency-Key here: a create sent
 * twice meets the store's code uniqueness (PRINTER_CODE_TAKEN), an update its version.
 */
export function useCreatePrinter() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (body: PrinterCreateRequest) => (await api.POST('/api/v1/backoffice/printers', { body })).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: PRINTERS_KEY }),
  });
}

export function useUpdatePrinter() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ id, body }: { id: string; body: PrinterUpdateRequest }) =>
      (await api.PUT('/api/v1/backoffice/printers/{id}', { params: { path: { id } }, body })).data,
    onSettled: () => queryClient.invalidateQueries({ queryKey: PRINTERS_KEY }),
  });
}
