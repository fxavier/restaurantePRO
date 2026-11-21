import {
  Button,
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
import { Check, X } from 'lucide-react';
import { useId, useState } from 'react';
import { useSession } from '../../identity';
import { holds, type FinanceCommand } from '../finance-commands';
import { CLOSING_FILTERS, closingStatuses } from '../finance-labels';
import { useFinanceCommand } from '../hooks/use-finance-commands';
import { useFinanceStepUp } from '../hooks/use-finance-step-up';
import { PAGE_SIZE, useClosings, useRegisters } from '../hooks/use-reports';
import { isStepUpRequired } from '../../../lib/step-up';
import type { Period } from '../period';
import type { ClosingStatus, ClosingView } from '../types';
import { CashDays } from './cash-days';
import { CommandAlert } from './command-alert';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { RejectClosingDialog } from './reject-closing-dialog';
import { selectClass } from './styles';

/**
 * "Fechos" (§8.3, §8.5): the closings the registers submitted, approved or rejected here, and the cash days of the
 * period, closed financially or reopened. Approving is a sensitive permission: the step-up keeps the command.
 */
export function ClosingsTab({ storeId, period }: { storeId: string; period: Period }) {
  const session = useSession();
  const canApprove = holds(session.data?.permissions, 'CASH_CLOSING_APPROVE', storeId);
  const [status, setStatus] = useState<ClosingStatus | ''>('SUBMITTED');
  const [page, setPage] = useState(0);
  const closings = useClosings(storeId, status || undefined, page);
  const registers = useRegisters(storeId);
  const command = useFinanceCommand();
  const stepUp = useFinanceStepUp(storeId);
  const [toReject, setToReject] = useState<ClosingView | null>(null);
  const filterId = useId();

  const registerName = (closing: ClosingView) =>
    registers.data?.find((register) => register.id === closing.registerId)?.name ?? closing.registerId ?? '—';
  const send = (next: FinanceCommand) =>
    command.mutate(next, { onError: (error) => isStepUpRequired(error) && stepUp.prompt(error, next) });
  const rows = closings.data?.rows ?? [];

  return (
    <div className="flex flex-col gap-6">
      {command.error && !isStepUpRequired(command.error) && (
        <CommandAlert error={command.error} pending={command.isPending} onRetry={() => command.variables && send(command.variables)} />
      )}
      <Card className="gap-4">
        <CardHeader className="flex flex-wrap items-end justify-between gap-3">
          <div className="flex flex-col gap-1.5">
            <CardTitle className="text-lg">Conferência e aprovação de fechos</CardTitle>
            <CardDescription>O esperado, o contado e a diferença de cada meio, como a caixa os submeteu.</CardDescription>
          </div>
          <div className="flex flex-col gap-1">
            <label htmlFor={filterId} className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
              Estado dos fechos
            </label>
            <select
              id={filterId}
              className={selectClass}
              value={status}
              onChange={(event) => {
                setStatus(event.target.value as ClosingStatus | '');
                setPage(0);
              }}
            >
              {CLOSING_FILTERS.map((filter) => (
                <option key={filter.value} value={filter.value}>
                  {filter.label}
                </option>
              ))}
            </select>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">
          <QueryState
            query={closings}
            empty={rows.length === 0}
            emptyMessage={status === 'SUBMITTED' ? 'Não há fechos por aprovar.' : 'Sem fechos.'}
            subject="os fechos desta loja"
          >
            <Table>
              <TableCaption className="sr-only">Fechos de caixa</TableCaption>
              <TableHeader>
                <TableRow>
                  <TableHead>Caixa</TableHead>
                  <TableHead>Ronda</TableHead>
                  <TableHead>Estado</TableHead>
                  <TableHead>
                    <span className="sr-only">Ações</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {rows.map((closing) => (
                  <TableRow key={closing.id}>
                    <TableCell className="font-medium">{registerName(closing)}</TableCell>
                    <TableCell className="tabular-nums">
                      {closing.dayRound}.{closing.attemptNo}
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={closing.status ?? ''} statuses={closingStatuses(closing.status)} />
                    </TableCell>
                    <TableCell>
                      {canApprove && closing.status === 'SUBMITTED' && (
                        <div className="flex justify-end gap-2">
                          <Button
                            size="sm"
                            aria-label={`Aprovar fecho ${registerName(closing)}`}
                            disabled={command.isPending}
                            onClick={() =>
                              send({
                                type: 'approveClosing',
                                closingId: closing.id!,
                                label: registerName(closing),
                                idempotencyKey: crypto.randomUUID(),
                              })
                            }
                          >
                            <Check aria-hidden="true" />
                            Aprovar
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            aria-label={`Rejeitar fecho ${registerName(closing)}`}
                            disabled={command.isPending}
                            onClick={() => setToReject(closing)}
                          >
                            <X aria-hidden="true" />
                            Rejeitar
                          </Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {rows.map((closing) => (
              <ClosingLines key={closing.id} closing={closing} name={`${registerName(closing)}, ronda ${closing.dayRound}`} />
            ))}
            <Pager page={page} totalElements={closings.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
          </QueryState>
        </CardContent>
      </Card>
      <CashDays storeId={storeId} period={period} />
      <RejectClosingDialog
        closing={toReject}
        name={toReject ? registerName(toReject) : ''}
        onCancel={() => setToReject(null)}
        onConfirm={(reason) => {
          const closing = toReject!;
          setToReject(null);
          send({
            type: 'rejectClosing',
            closingId: closing.id!,
            label: registerName(closing),
            reason,
            idempotencyKey: crypto.randomUUID(),
          });
        }}
      />
      {stepUp.dialog}
    </div>
  );
}

/** Stitch's "Discriminação por modalidade": one row per method, the amounts as the server computed them. */
function ClosingLines({ closing, name }: { closing: ClosingView; name: string }) {
  return (
    <div className="rounded-lg border p-3">
      <Table>
        <TableCaption className="text-left">Fecho {name}</TableCaption>
        <TableHeader>
          <TableRow>
            <TableHead>Meio</TableHead>
            <TableHead className="text-right">Esperado</TableHead>
            <TableHead className="text-right">Contado</TableHead>
            <TableHead className="text-right">Diferença</TableHead>
            <TableHead>Justificação</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {(closing.lines ?? []).map((line) => (
            <TableRow key={line.methodCode}>
              <TableCell className="font-mono">{line.methodCode}</TableCell>
              <TableCell className="text-right">{line.expected ? <Money value={line.expected} /> : '—'}</TableCell>
              <TableCell className="text-right">{line.counted ? <Money value={line.counted} /> : '—'}</TableCell>
              <TableCell className="text-right font-medium">{line.difference ? <Money value={line.difference} /> : '—'}</TableCell>
              <TableCell className="text-muted-foreground">{line.justification ?? ''}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {closing.rejectionReason && <p className="mt-2 text-sm text-muted-foreground">Motivo da rejeição: {closing.rejectionReason}</p>}
    </div>
  );
}
