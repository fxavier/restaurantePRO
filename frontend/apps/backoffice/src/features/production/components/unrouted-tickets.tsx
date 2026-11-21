import { Button } from '@restaurantpro/ui';
import type { UseQueryResult } from '@tanstack/react-query';
import { Send, Unplug } from 'lucide-react';
import type { ReactNode } from 'react';
import { useRouteTicket, type TicketPage } from '../hooks/use-tickets';
import { jobTitle } from '../labels';
import type { Ticket } from '../parse';
import { ProblemAlert } from './problem-alert';
import { QueryState } from './query-state';

type UnroutedTicketsProps = {
  tickets: UseQueryResult<TicketPage>;
  /** PRODUCTION_OPERATE in this store: only then is "Encaminhar" offered. */
  mayOperate: boolean;
};

/**
 * Tickets that found no route when they were made (D89): nothing printed them. Once a route exists, "Encaminhar"
 * sends each to its printer; the server says when there is still none (STILL_UNROUTED).
 */
export function UnroutedTickets({ tickets, mayOperate }: UnroutedTicketsProps) {
  const route = useRouteTicket();
  const send = (ticketId: string) => {
    route.reset();
    route.mutate({ ticketId, idempotencyKey: crypto.randomUUID() });
  };
  const resend = () => route.variables && route.mutate(route.variables);
  return (
    <section aria-labelledby="unrouted-title" className="flex flex-col gap-3 rounded-xl border bg-card p-4">
      <h2 id="unrouted-title" className="flex items-center gap-2 text-lg font-semibold">
        <Unplug aria-hidden="true" className="size-5 text-warning" />
        Talões sem rota
      </h2>
      {route.error ? <ProblemAlert error={route.error} pending={route.isPending} onRetry={resend} /> : null}
      <QueryState
        query={tickets}
        subject="os talões"
        isEmpty={(data) => data.tickets.length === 0}
        emptyMessage="Todos os talões têm rota."
      >
        {(data) => (
          <ul aria-label="Talões por encaminhar" className="flex flex-col gap-2">
            {data.tickets.map((ticket) => (
              <TicketItem key={ticket.id} ticket={ticket}>
                {mayOperate && (
                  <Button variant="outline" size="sm" disabled={route.isPending} onClick={() => send(ticket.id)}>
                    <Send aria-hidden="true" />
                    Encaminhar
                  </Button>
                )}
              </TicketItem>
            ))}
          </ul>
        )}
      </QueryState>
    </section>
  );
}

function TicketItem({ ticket, children }: { ticket: Ticket; children: ReactNode }) {
  const title = jobTitle(ticket.sourceType, ticket.destination);
  return (
    <li aria-label={title} className="flex flex-wrap items-center gap-2 rounded-md border bg-muted p-3 text-sm">
      <span className="flex min-w-0 flex-1 flex-col">
        <span className="font-medium">{title}</span>
        {ticket.lines?.length ? <span className="text-muted-foreground">{ticket.lines.join(', ')}</span> : null}
      </span>
      {children}
    </li>
  );
}
