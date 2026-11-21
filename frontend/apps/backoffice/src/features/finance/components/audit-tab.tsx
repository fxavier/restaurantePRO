import { Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { useState } from 'react';
import { formatInstant } from '../format';
import { PAGE_SIZE, useAuditEvents } from '../hooks/use-reports';
import type { Period } from '../period';
import type { AuditEventView } from '../types';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { SectionCard } from './section-card';

const ACTORS: Record<string, string> = { ACCOUNT: 'Conta', EMPLOYEE: 'Empregado', SYSTEM: 'Sistema', TERMINAL: 'Terminal' };

/** "Auditoria" (§5, §10): what was done in the store in the period, append-only, newest first, in the store's time. */
export function AuditTab({ storeId, period }: { storeId: string; period: Period }) {
  const [page, setPage] = useState(0);
  const events = useAuditEvents(storeId, period, page);
  const rows = events.data?.rows ?? [];
  return (
    <SectionCard
      title="Auditoria de eventos"
      description="Registo só de acréscimo: cada operação sensível, com quem a fez, quem a aprovou e o motivo."
    >
      <QueryState query={events} empty={rows.length === 0} emptyMessage="Sem eventos neste período." subject="a auditoria desta loja">
        <Table>
          <TableCaption className="sr-only">Eventos de auditoria</TableCaption>
          <TableHeader>
            <TableRow>
              <TableHead>Hora</TableHead>
              <TableHead>Evento</TableHead>
              <TableHead>Entidade</TableHead>
              <TableHead>Autor</TableHead>
              <TableHead>Motivo</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((event) => (
              <EventRow key={event.id} event={event} timeZone={period.timeZone} />
            ))}
          </TableBody>
        </Table>
        <Pager page={page} totalElements={events.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
      </QueryState>
    </SectionCard>
  );
}

function EventRow({ event, timeZone }: { event: AuditEventView; timeZone: string }) {
  const actor = event.actorKind ?? '';
  return (
    <TableRow>
      <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(event.occurredAt, timeZone)}</TableCell>
      <TableCell className="font-mono text-xs">{event.action}</TableCell>
      <TableCell className="text-muted-foreground">{event.entityType}</TableCell>
      <TableCell>
        {ACTORS[actor] ?? actor}
        {event.approverId && <span className="block text-xs text-muted-foreground">Com aprovação</span>}
      </TableCell>
      <TableCell>{event.reason}</TableCell>
    </TableRow>
  );
}
