import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendWaitingWhileInProgress } from '../../../lib/in-progress';
import { parseTickets, type Ticket } from '../parse';
import { PRINT_JOBS_KEY } from './use-printer-commands';

export const TICKETS_KEY = ['backoffice', 'production-tickets'] as const;

export type TicketPage = { tickets: Ticket[]; totalElements: number };

/** The store's tickets that found no printer (UNROUTED, D89): the first page, oldest problems the server's order. */
export function useUnroutedTickets(storeId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...TICKETS_KEY, storeId, 'UNROUTED'],
    queryFn: async (): Promise<TicketPage> => {
      const { data } = await api.GET('/api/v1/backoffice/production-tickets', {
        params: { query: { storeId, routing: 'UNROUTED', page: 0, size: 20 } },
      });
      return { tickets: parseTickets(data?.content ?? []), totalElements: data?.totalElements ?? 0 };
    },
  });
}

/** Sends an UNROUTED ticket to the printer its route now names: one intent per tap, one key for every resend. */
export function useRouteTicket() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ ticketId, idempotencyKey }: { ticketId: string; idempotencyKey: string }) =>
      sendWaitingWhileInProgress(() =>
        api.POST('/api/v1/backoffice/production-tickets/{id}/route', {
          params: { path: { id: ticketId }, header: { 'Idempotency-Key': idempotencyKey } },
        }),
      ),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: TICKETS_KEY }),
        queryClient.invalidateQueries({ queryKey: PRINT_JOBS_KEY }),
      ]),
  });
}
