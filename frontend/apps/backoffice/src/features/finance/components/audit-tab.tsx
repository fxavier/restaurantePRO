import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { useState } from 'react';
import { formatInstant } from '../format';
import { PAGE_SIZE, useAuditEvents } from '../hooks/use-reports';
import type { Period } from '../period';
import { Pager } from './pager';
import { QueryState } from './query-state';

const ACTORS: Record<string, string> = { ACCOUNT: 'Conta', EMPLOYEE: 'Empregado', SYSTEM: 'Sistema', TERMINAL: 'Terminal' };

/** "Auditoria" (§5, §10): what was done in the store in the period, append-only, newest first, in the store's time. */
export function AuditTab({ storeId, period }: { storeId: string; period: Period }) {
  const [page, setPage] = useState(0);
  const events = useAuditEvents(storeId, period, page);
  const rows = events.data?.rows ?? [];
  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Auditoria de eventos</CardTitle>
        <CardDescription>Registo só de acréscimo: cada operação sensível, com quem a fez, quem a aprovou e o motivo.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
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
                <TableRow key={event.id}>
                  <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(event.occurredAt, period.timeZone)}</TableCell>
                  <TableCell className="font-mono text-xs">{event.action}</TableCell>
                  <TableCell className="text-muted-foreground">{event.entityType}</TableCell>
                  <TableCell>
                    {ACTORS[event.actorKind ?? ''] ?? event.actorKind ?? '—'}
                    {event.approverId && <span className="block text-xs text-muted-foreground">Com aprovação</span>}
                  </TableCell>
                  <TableCell>{event.reason ?? ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <Pager page={page} totalElements={events.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </CardContent>
    </Card>
  );
}
