import { Button, StatusBadge, Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { useState } from 'react';
import { closeKindLabel, directionLabel, SESSION_FILTERS, sessionStatuses } from '../finance-labels';
import { formatInstant } from '../format';
import { PAGE_SIZE, useCashSessions, useSessionAdjustments } from '../hooks/use-reports';
import type { Period } from '../period';
import type { CashSessionStatus, SessionRow } from '../types';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { Amount, SectionCard } from './section-card';
import { StatusFilter } from './status-filter';

/**
 * "Sessões de caixa" (§5, §8.3): the store's cash sessions opened in the period, newest first, with the opening float
 * and the operator's blind count per method, as the server stored them (the expected amounts are the closing's); a
 * closed session opens its adjustments (D79), the later corrections the BackOffice recorded on it. The status filter stays when the period changes; the list starts again at its first page when either changes (a past
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
      <SessionsTable rows={rows} timeZone={period.timeZone} />
      <Pager page={page} totalElements={sessions.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
    </QueryState>
  );
}

function SessionsTable({ rows, timeZone }: { rows: SessionRow[]; timeZone: string }) {
  const [openId, setOpenId] = useState<string | null>(null);
  const opened = rows.find((row) => row.id === openId);
  return (
    <>
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
            <TableHead>
              <span className="sr-only">Detalhe</span>
            </TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id}>
              <TableCell className="font-mono font-medium">{row.registerCode}</TableCell>
              <TableCell>{row.operatorName ?? row.operatorEmployeeId}</TableCell>
              <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(row.openedAt, timeZone)}</TableCell>
              <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(row.closedAt ?? undefined, timeZone)}</TableCell>
              <TableCell>
                <StatusBadge look="tint" status={row.status} statuses={sessionStatuses(row.status)} />
                <span className="mt-1 block text-xs text-muted-foreground">{closeKindLabel(row.closeKind)}</span>
              </TableCell>
              <TableCell className="text-right">
                <Amount value={row.openingFloat} />
              </TableCell>
              <TableCell>
                <Counted row={row} />
              </TableCell>
              <TableCell>
                {row.status === 'CLOSED' && (
                  <AdjustmentsToggle row={row} open={row.id === openId} onToggle={() => setOpenId(row.id === openId ? null : row.id)} />
                )}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {opened && <SessionAdjustments key={opened.id} session={opened} timeZone={timeZone} />}
    </>
  );
}

const adjustmentsPanelId = (row: SessionRow) => `session-adjustments-${row.id}`;

/** Opens or closes a closed session's adjustments below the table; only adjustments are listed (an open session has none). */
function AdjustmentsToggle({ row, open, onToggle }: { row: SessionRow; open: boolean; onToggle: () => void }) {
  const Icon = open ? ChevronUp : ChevronDown;
  return (
    <div className="flex justify-end">
      <Button
        size="sm"
        variant="outline"
        aria-label={`${open ? 'Ocultar' : 'Ver'} ajustes da sessão ${row.registerCode}`}
        aria-expanded={open}
        aria-controls={open ? adjustmentsPanelId(row) : undefined}
        onClick={onToggle}
      >
        <Icon aria-hidden="true" />
        Ajustes
      </Button>
    </div>
  );
}

/**
 * A session's adjustments (D79 (1)): the corrections recorded after it closed, newest first as the server sends them,
 * each amount as stored with the direction it moved the register (no sum here: FC §12).
 */
function SessionAdjustments({ session, timeZone }: { session: SessionRow; timeZone: string }) {
  const adjustments = useSessionAdjustments(session.id);
  const rows = adjustments.data ?? [];
  return (
    <div id={adjustmentsPanelId(session)} className="rounded-xl border p-3">
      <QueryState query={adjustments} empty={rows.length === 0} emptyMessage="Sem ajustes nesta sessão." subject="os ajustes desta sessão">
        <Table>
          <TableCaption className="text-left">
            Ajustes da sessão {session.registerCode}, aberta em {formatInstant(session.openedAt, timeZone)}
          </TableCaption>
          <TableHeader>
            <TableRow>
              <TableHead>Registado em</TableHead>
              <TableHead>Sentido</TableHead>
              <TableHead>Meio</TableHead>
              <TableHead className="text-right">Valor</TableHead>
              <TableHead>Motivo</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((adjustment) => (
              <TableRow key={adjustment.id}>
                <TableCell className="whitespace-nowrap tabular-nums">{formatInstant(adjustment.occurredAt, timeZone)}</TableCell>
                <TableCell>{directionLabel(adjustment.direction)}</TableCell>
                <TableCell className="font-mono">{adjustment.methodCode}</TableCell>
                <TableCell className="text-right">
                  <Amount value={adjustment.amount} />
                </TableCell>
                <TableCell className="text-muted-foreground">{adjustment.reason}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </QueryState>
    </div>
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
