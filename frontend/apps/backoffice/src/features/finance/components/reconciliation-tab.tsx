import { StatusBadge, Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { Hourglass } from 'lucide-react';
import { closingStatuses, nameById } from '../finance-labels';
import { formatBusinessDate, formatInstant } from '../format';
import { useCashReconciliation, useInDoubtAttempts, useRegisters } from '../hooks/use-reports';
import type { Period } from '../period';
import type { InDoubtAttemptView, ReconciliationLine } from '../types';
import { QueryState } from './query-state';
import { Amount, SectionCard } from './section-card';

const IN_DOUBT = { PENDING: { label: 'Por reconciliar', icon: Hourglass, tone: 'warning' as const } };

/**
 * "Reconciliação" (§7): the cash reconciliation of the period, and the payments whose outcome is still unknown. Those
 * are pending reconciliation, never failed: the server resolves them with the provider.
 */
export function ReconciliationTab({ storeId, period }: { storeId: string; period: Period }) {
  const lines = useCashReconciliation(storeId, period);
  const inDoubt = useInDoubtAttempts(storeId);
  useRegisters(storeId); // started with the lines, so the rows have their register names when they show
  return (
    <div className="flex flex-col gap-6">
      <SectionCard title="Reconciliação de caixa" description="Por dia, caixa e meio: o esperado, o contado, os acertos e a diferença.">
        <QueryState query={lines} empty={!lines.data?.length} emptyMessage="Sem movimentos de caixa neste período." subject="a caixa desta loja">
          <LinesTable storeId={storeId} lines={lines.data ?? []} />
        </QueryState>
      </SectionCard>
      <SectionCard
        title="Pagamentos por reconciliar"
        description="Pagamentos sem resposta conhecida do fornecedor. Não foram recusados: o sistema confirma-os com o fornecedor."
      >
        <QueryState query={inDoubt} empty={!inDoubt.data?.length} emptyMessage="Não há pagamentos por reconciliar." subject="a reconciliação desta loja">
          <InDoubtTable attempts={inDoubt.data ?? []} timeZone={period.timeZone} />
        </QueryState>
      </SectionCard>
    </div>
  );
}

function LinesTable({ storeId, lines }: { storeId: string; lines: ReconciliationLine[] }) {
  const registers = useRegisters(storeId);
  return (
    <Table>
      <TableCaption className="sr-only">Reconciliação de caixa</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Dia</TableHead>
          <TableHead>Caixa</TableHead>
          <TableHead>Meio</TableHead>
          <TableHead>Fecho</TableHead>
          <TableHead className="text-right">Esperado</TableHead>
          <TableHead className="text-right">Contado</TableHead>
          <TableHead className="text-right">Acertos</TableHead>
          <TableHead className="text-right">Diferença</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {lines.map((line) => (
          <TableRow key={`${line.cashDayId}-${line.dayRound}-${line.registerId}-${line.methodCode}`}>
            <TableCell className="tabular-nums">{formatBusinessDate(line.businessDate)}</TableCell>
            <TableCell>{nameById(registers.data, line.registerId)}</TableCell>
            <TableCell className="font-mono">{line.methodCode}</TableCell>
            <TableCell>
              <StatusBadge look="tint" status={line.closingStatus ?? ''} statuses={closingStatuses(line.closingStatus)} />
            </TableCell>
            <TableCell className="text-right">
              <Amount value={line.expected} />
            </TableCell>
            <TableCell className="text-right">
              <Amount value={line.counted} />
            </TableCell>
            <TableCell className="text-right">
              <Amount value={line.adjustments} />
            </TableCell>
            <TableCell className="text-right font-medium">
              <Amount value={line.difference} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}

function InDoubtTable({ attempts, timeZone }: { attempts: InDoubtAttemptView[]; timeZone: string }) {
  return (
    <Table>
      <TableCaption className="sr-only">Pagamentos por reconciliar</TableCaption>
      <TableHeader>
        <TableRow>
          <TableHead>Criado</TableHead>
          <TableHead>Meio</TableHead>
          <TableHead>Estado</TableHead>
          <TableHead className="text-right">Consultas</TableHead>
          <TableHead className="text-right">Montante</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {attempts.map((attempt) => (
          <TableRow key={attempt.attemptId}>
            <TableCell className="tabular-nums">{formatInstant(attempt.createdAt, timeZone)}</TableCell>
            <TableCell className="font-mono">{attempt.methodCode}</TableCell>
            <TableCell>
              <StatusBadge look="tint" status="PENDING" statuses={IN_DOUBT} />
            </TableCell>
            <TableCell className="text-right tabular-nums">{attempt.pollCount ?? 0}</TableCell>
            <TableCell className="text-right font-medium">
              <Amount value={attempt.amount} />
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
