import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  StatusBadge,
  Table,
  TableBody,
  TableCaption,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@restaurantpro/ui';
import { Lock, LockOpen } from 'lucide-react';
import { useState } from 'react';
import { isStepUpRequired } from '../../../lib/step-up';
import { useSession } from '../../identity';
import { holds, type FinanceCommand } from '../finance-commands';
import { cashDays, dayStatuses, type CashDay } from '../finance-labels';
import { formatBusinessDate } from '../format';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { useFinanceStepUp } from '../hooks/use-finance-step-up';
import { useCashReconciliation } from '../hooks/use-reports';
import type { Period } from '../period';
import { CommandAlert } from './command-alert';
import { QueryState } from './query-state';
import { ReopenDayDialog } from './reopen-day-dialog';

/**
 * The cash days of the period (§8.3): an operationally closed day is closed financially once its closings are
 * approved (the server checks); a closed day is reopened with a reason and an approver (D40).
 */
export function CashDays({ storeId, period }: { storeId: string; period: Period }) {
  const session = useSession();
  const permissions = session.data?.permissions;
  const lines = useCashReconciliation(storeId, period);
  const close = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);
  const [toClose, setToClose] = useState<CashDay | null>(null);
  const [toReopen, setToReopen] = useState<CashDay | null>(null);
  const days = cashDays(lines.data ?? []);

  const send = (command: FinanceCommand) =>
    close.mutate(command, { onError: (error) => isStepUpRequired(error) && stepUp.prompt(error, command) });
  const canReopen = (day: CashDay) =>
    holds(permissions, day.status === 'FINANCIALLY_CLOSED' ? 'CASH_DAY_REOPEN' : 'CASH_DAY_CLOSE', storeId);
  const canClose = (day: CashDay) =>
    day.status === 'OPERATIONALLY_CLOSED' && holds(permissions, 'CASH_CLOSING_APPROVE', storeId);

  return (
    <Card className="gap-4">
      <CardHeader>
        <CardTitle className="text-lg">Dias de caixa</CardTitle>
        <CardDescription>O fecho financeiro do dia pede todos os fechos das caixas aprovados.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {close.error && !isStepUpRequired(close.error) && (
          <CommandAlert error={close.error} pending={close.isPending} onRetry={() => close.variables && send(close.variables)} />
        )}
        <QueryState query={lines} empty={days.length === 0} emptyMessage="Sem dias de caixa neste período." subject="a caixa desta loja">
          <Table>
            <TableCaption className="sr-only">Dias de caixa</TableCaption>
            <TableHeader>
              <TableRow>
                <TableHead>Dia</TableHead>
                <TableHead>Estado</TableHead>
                <TableHead>
                  <span className="sr-only">Ações</span>
                </TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {days.map((day) => {
                const date = formatBusinessDate(day.businessDate);
                return (
                  <TableRow key={day.id}>
                    <TableCell className="font-medium tabular-nums">{date}</TableCell>
                    <TableCell>
                      <StatusBadge status={day.status} statuses={dayStatuses(day.status)} />
                    </TableCell>
                    <TableCell>
                      <div className="flex justify-end gap-2">
                        {canClose(day) && (
                          <Button size="sm" aria-label={`Fecho financeiro do dia ${date}`} disabled={close.isPending} onClick={() => setToClose(day)}>
                            <Lock aria-hidden="true" />
                            Fecho financeiro
                          </Button>
                        )}
                        {day.status !== 'OPEN' && canReopen(day) && (
                          <Button size="sm" variant="outline" aria-label={`Reabrir o dia ${date}`} onClick={() => setToReopen(day)}>
                            <LockOpen aria-hidden="true" />
                            Reabrir
                          </Button>
                        )}
                      </div>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </QueryState>
      </CardContent>
      <AlertDialog open={toClose !== null} onOpenChange={(open) => !open && setToClose(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Fecho financeiro do dia {formatBusinessDate(toClose?.businessDate)}?</AlertDialogTitle>
            <AlertDialogDescription>
              O dia fica fechado para a contabilidade. Para o corrigir depois, é preciso reabri-lo com um aprovador.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancelar</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                const day = toClose!;
                send({
                  type: 'financialClose',
                  dayId: day.id,
                  label: formatBusinessDate(day.businessDate),
                  idempotencyKey: crypto.randomUUID(),
                });
              }}
            >
              Fechar o dia
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
      <ReopenDayDialog day={toReopen} onClose={() => setToReopen(null)} />
      {stepUp.dialog}
    </Card>
  );
}
