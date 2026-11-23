import { StatusBadge, Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { useState } from 'react';
import { useSession } from '../../identity';
import { holds } from '../finance-commands';
import { closeKindLabel, nameById, SESSION_FILTERS, sessionStatuses } from '../finance-labels';
import { formatInstant } from '../format';
import { PAGE_SIZE, useCashSessions, useEmployees } from '../hooks/use-reports';
import type { Period } from '../period';
import type { CashSessionStatus, SessionRow } from '../types';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { Amount, SectionCard } from './section-card';
import { StatusFilter } from './status-filter';

/**
 * "Sessões de caixa" (§5, §8.3): the store's cash sessions opened in the period, newest first, with the opening float
 * and the operator's blind count per method, as the server stored them (the expected amounts are the closing's). The
 * status filter stays when the period changes; the list starts again at its first page when either changes (a past
 * page may not exist in the new search).
 */
export function CashSessionsTab({ storeId, period, periodKey }: { storeId: string; period: Period; periodKey: string }) {
  const [status, setStatus] = useState<CashSessionStatus | ''>('');
  return (
    <SectionCard
      title="Sessões de caixa"
      description="Cada abertura de caixa do período: quem a operou, o fundo inicial e a contagem cega do fecho."
      action={<StatusFilter label="Estado das sessões" options={SESSION_FILTERS} value={status} onChange={setStatus} />}
    >
      <SessionsList key={`${periodKey}/${status}`} storeId={storeId} period={period} status={status || undefined} />
    </SectionCard>
  );
}

/** One search's sessions, page by page. */
function SessionsList({ storeId, period, status }: { storeId: string; period: Period; status?: CashSessionStatus }) {
  const [page, setPage] = useState(0);
  const sessions = useCashSessions(storeId, period, status, page);
  const rows = sessions.data?.rows ?? [];
  return (
    <QueryState query={sessions} empty={rows.length === 0} emptyMessage="Sem sessões de caixa neste período." subject="as sessões de caixa desta loja">
      <SessionsTable storeId={storeId} rows={rows} timeZone={period.timeZone} />
      <Pager page={page} totalElements={sessions.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
    </QueryState>
  );
}

function SessionsTable({ storeId, rows, timeZone }: { storeId: string; rows: SessionRow[]; timeZone: string }) {
  const session = useSession();
  const employees = useEmployees(storeId, holds(session.data?.permissions, 'EMPLOYEE_MANAGE', storeId));
  return (
    <Table>
      <TableCaption className="sr-only">Sessões de caixa</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Caixa</TableHead>
          <TableHead>Operador</TableHead>
          <TableHead>Abertura</TableHead>
          <TableHead>Fecho</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead className="text-right">Fundo inicial</TableHead>
          <TableHead>Contado</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {rows.map((row) => (
          <TableRow key={row.id}>
            <TableCell className="font-mono font-medium">{row.registerCode}</TableCell>
            <TableCell>{nameById(employees.data, row.operatorEmployeeId)}</TableCell>
            <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(row.openedAt, timeZone)}</TableCell>
            <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(row.closedAt ?? undefined, timeZone)}</TableCell>
            <TableCell>
              <StatusBadge status={row.status} statuses={sessionStatuses(row.status)} />
              <span className="mt-1 block text-xs text-muted-foreground">{closeKindLabel(row.closeKind)}</span>
            </TableCell>
            <TableCell className="text-right">
              <Amount value={row.openingFloat} />
            </TableCell>
            <TableCell>
              <Counted row={row} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

/** The blind count per method, as the operator declared it; nothing while the session is open. */
function Counted({ row }: { row: SessionRow }) {
  if (row.counted.length === 0) return <>—</>;
  return (
    <dl className="flex flex-col gap-0.5 text-sm">
      {row.counted.map((line) => (
        <div key={line.methodCode} className="flex items-baseline justify-between gap-3">
          <dt className="font-mono text-xs text-muted-foreground">{line.methodCode}</dt>
          <dd className="tabular-nums">
            <Amount value={line.amount} />
          </dd>
        </div>
      ))}
    </dl>
  );
}
