import { useQuery } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { parsePrinters, type Printer } from '../parse';

export const PRINTERS_KEY = ['backoffice', 'printers'] as const;

/** A store has a handful of printers: one page large enough for all of them, the screen shows them as cards. */
const PRINTER_PAGE = { page: 0, size: 100 };

export function usePrinters(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...PRINTERS_KEY, storeId],
    queryFn: async (): Promise<Printer[]> => {
      const { data } = await api.GET('/api/v1/backoffice/printers', { params: { query: { storeId, ...PRINTER_PAGE } } });
      return parsePrinters(data?.content ?? []);
    },
  });
}
