import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Money,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Hourglass } from 'lucide-react';
import { closingStatuses } from '../finance-labels';
import { formatBusinessDate, formatInstant } from '../format';
import { useCashReconciliation, useInDoubtAttempts, useRegisters } from '../hooks/use-reports';
import type { Period } from '../period';
import type { Money as MoneyValue } from '../types';
import { QueryState } from './query-state';

const IN_DOUBT = { PENDING: { label: 'Por reconciliar', icon: Hourglass, tone: 'warning' as const } };

/**
 * "Reconciliação" (§7): the cash reconciliation of the period, and the payments whose outcome is still unknown. Those
 * are pending reconciliation, never failed: the server resolves them with the provider.
 */
export function ReconciliationTab({ storeId, period }: { storeId: string; period: Period }) {
  const lines = useCashReconciliation(storeId, period);
  const registers = useRegisters(storeId);
  const inDoubt = useInDoubtAttempts(storeId);
  const registerName = (id: string | undefined) => registers.data?.find((register) => register.id === id)?.name ?? id ?? '—';

  return (
    <div className="flex flex-col gap-6">
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Reconciliação de caixa</CardTitle>
          <CardDescription>Por dia, caixa e meio: o esperado, o contado, os acertos e a diferença.</CardDescription>
        </CardHeader>
        <CardContent>
          <QueryState query={lines} empty={!lines.data?.length} emptyMessage="Sem movimentos de caixa neste período." subject="a caixa desta loja">
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
                {lines.data?.map((line) => (
                  <TableRow key={`${line.cashDayId}-${line.dayRound}-${line.registerId}-${line.methodCode}`}>
                    <TableCell className="tabular-nums">{formatBusinessDate(line.businessDate)}</TableCell>
                    <TableCell>{registerName(line.registerId)}</TableCell>
                    <TableCell className="font-mono">{line.methodCode}</TableCell>
                    <TableCell>
                      {line.closingStatus ? (
                        <StatusBadge status={line.closingStatus} statuses={closingStatuses(line.closingStatus)} />
                      ) : (
                        '—'
                      )}
                    </TableCell>
                    <TableCell className="text-right">{amount(line.expected)}</TableCell>
                    <TableCell className="text-right">{amount(line.counted)}</TableCell>
                    <TableCell className="text-right">{amount(line.adjustments)}</TableCell>
                    <TableCell className="text-right font-medium">{amount(line.difference)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </QueryState>
        </CardContent>
      </Card>
      <Card className="gap-4">
        <CardHeader>
          <CardTitle className="text-lg">Pagamentos por reconciliar</CardTitle>
          <CardDescription>
            Pagamentos sem resposta conhecida do fornecedor. Não foram recusados: o sistema confirma-os com o fornecedor.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <QueryState query={inDoubt} empty={!inDoubt.data?.length} emptyMessage="Não há pagamentos por reconciliar." subject="a reconciliação desta loja">
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
                {inDoubt.data?.map((attempt) => (
                  <TableRow key={attempt.attemptId}>
                    <TableCell className="tabular-nums">{formatInstant(attempt.createdAt, period.timeZone)}</TableCell>
                    <TableCell className="font-mono">{attempt.methodCode}</TableCell>
                    <TableCell>
                      <StatusBadge status="PENDING" statuses={IN_DOUBT} />
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{attempt.pollCount ?? 0}</TableCell>
                    <TableCell className="text-right font-medium">{amount(attempt.amount)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </QueryState>
        </CardContent>
      </Card>
    </div>
  );
}

function amount(value: MoneyValue | undefined) {
  return value ? <Money value={value} /> : '—';
}
