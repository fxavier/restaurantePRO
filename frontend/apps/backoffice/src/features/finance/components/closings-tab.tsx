import { Button, StatusBadge, Table, TableBody, TableCaption, TableCell, TableHead, TableHeader, TableRow } from '@restaurantpro/ui';
import { Check, X } from 'lucide-react';
import { useState } from 'react';
import { useSession } from '../../identity';
import { holds } from '../finance-commands';
import { CLOSING_FILTERS, closingStatuses, nameById } from '../finance-labels';
import { PAGE_SIZE, useClosings, useRegisters } from '../hooks/use-reports';
import { useSensitiveCommand } from '../hooks/use-sensitive-command';
import type { Period } from '../period';
import type { ClosingStatus, ClosingView } from '../types';
import { CashDays } from './cash-days';
import { CommandAlert } from './command-alert';
import { Pager } from './pager';
import { QueryState } from './query-state';
import { RejectClosingDialog } from './reject-closing-dialog';
import { Amount, SectionCard } from './section-card';
import { StatusFilter } from './status-filter';

/**
 * "Fechos" (§8.3, §8.5): the closings the registers submitted, approved or rejected here, and the cash days of the
 * period, closed financially or reopened. Approving is a sensitive permission: the step-up keeps the command.
 */
export function ClosingsTab({ storeId, period }: { storeId: string; period: Period }) {
  const [status, setStatus] = useState<ClosingStatus | ''>('SUBMITTED');
  const [page, setPage] = useState(0);
  const closings = useClosings(storeId, status || undefined, page);
  useRegisters(storeId); // started with the list, so the rows have their register names when they show
  const rows = closings.data?.rows ?? [];
  const filter = (
    <StatusFilter
      label="Estado dos fechos"
      options={CLOSING_FILTERS}
      value={status}
      onChange={(next) => {
        setStatus(next);
        setPage(0);
      }}
    />
  );
  return (
    <div className="flex flex-col gap-6">
      <SectionCard
        title="Conferência e aprovação de fechos"
        description="O esperado, o contado e a diferença de cada meio, como a caixa os submeteu."
        action={filter}
      >
        <QueryState
          query={closings}
          empty={rows.length === 0}
          emptyMessage={status === 'SUBMITTED' ? 'Não há fechos por aprovar.' : 'Sem fechos.'}
          subject="os fechos desta loja"
        >
          <ClosingsTable storeId={storeId} rows={rows} />
          <Pager page={page} totalElements={closings.data?.totalElements ?? 0} pageSize={PAGE_SIZE} onPage={setPage} />
        </QueryState>
      </SectionCard>
      <CashDays storeId={storeId} period={period} />
    </div>
  );
}

function ClosingsTable({ storeId, rows }: { storeId: string; rows: ClosingView[] }) {
  const session = useSession();
  const canApprove = holds(session.data?.permissions, 'CASH_CLOSING_APPROVE', storeId);
  const registers = useRegisters(storeId);
  const command = useSensitiveCommand(storeId);
  const [toReject, setToReject] = useState<ClosingView | null>(null);
  const nameOf = (closing: ClosingView | null) => nameById(registers.data, closing?.registerId);

  const approve = (closing: ClosingView) =>
    command.send({ type: 'approveClosing', closingId: closing.id!, label: nameOf(closing), idempotencyKey: crypto.randomUUID() });
  const reject = (closing: ClosingView, reason: string) =>
    command.send({ type: 'rejectClosing', closingId: closing.id!, label: nameOf(closing), reason, idempotencyKey: crypto.randomUUID() });

  return (
    <>
      {command.failure && <CommandAlert {...command.failure} />}
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
            <ClosingRow
              key={closing.id}
              closing={closing}
              name={nameOf(closing)}
              decidable={canApprove && closing.status === 'SUBMITTED'}
              pending={command.pending}
              onApprove={() => approve(closing)}
              onReject={() => setToReject(closing)}
            />
          ))}
        </TableBody>
      </Table>
      {rows.map((closing) => (
        <ClosingLines key={closing.id} closing={closing} name={`${nameOf(closing)}, ronda ${closing.dayRound}`} />
      ))}
      <RejectClosingDialog
        closing={toReject}
        name={nameOf(toReject)}
        onCancel={() => setToReject(null)}
        onConfirm={(closing, reason) => {
          setToReject(null);
          reject(closing, reason);
        }}
      />
      {command.dialog}
    </>
  );
}

type ClosingRowProps = {
  closing: ClosingView;
  name: string;
  /** Approve and reject are offered: the closing waits for a decision and the user may take it (the server decides). */
  decidable: boolean;
  pending: boolean;
  onApprove: () => void;
  onReject: () => void;
};

function ClosingRow({ closing, name, decidable, pending, onApprove, onReject }: ClosingRowProps) {
  return (
    <TableRow>
      <TableCell className="font-medium">{name}</TableCell>
      <TableCell className="tabular-nums">
        {closing.dayRound}.{closing.attemptNo}
      </TableCell>
      <TableCell>
        <StatusBadge look="tint" status={closing.status ?? ''} statuses={closingStatuses(closing.status)} />
      </TableCell>
      <TableCell>
        {decidable && (
          <div className="flex justify-end gap-2">
            <Button size="sm" aria-label={`Aprovar fecho ${name}`} disabled={pending} onClick={onApprove}>
              <Check aria-hidden="true" />
              Aprovar
            </Button>
            <Button size="sm" variant="outline" aria-label={`Rejeitar fecho ${name}`} disabled={pending} onClick={onReject}>
              <X aria-hidden="true" />
              Rejeitar
            </Button>
          </div>
        )}
      </TableCell>
    </TableRow>
  );
}

/** Stitch's "Discriminação por modalidade": one row per method, the amounts as the server computed them. */
function ClosingLines({ closing, name }: { closing: ClosingView; name: string }) {
  return (
    <div className="rounded-xl border p-3">
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
          {closing.lines?.map((line) => (
            <TableRow key={line.methodCode}>
              <TableCell className="font-mono">{line.methodCode}</TableCell>
              <TableCell className="text-right">
                <Amount value={line.expected} />
              </TableCell>
              <TableCell className="text-right">
                <Amount value={line.counted} />
              </TableCell>
              <TableCell className="text-right font-medium">
                <Amount value={line.difference} />
              </TableCell>
              <TableCell className="text-muted-foreground">{line.justification}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
      {closing.rejectionReason && <p className="mt-2 text-sm text-muted-foreground">Motivo da rejeição: {closing.rejectionReason}</p>}
    </div>
  );
}
