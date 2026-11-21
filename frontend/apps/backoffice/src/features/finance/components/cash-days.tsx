import {
  Button,
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
import { useSession } from '../../identity';
import { holds } from '../finance-commands';
import { cashDays, dayStatuses, type CashDay } from '../finance-labels';
import { formatBusinessDate } from '../format';
import { useCashReconciliation } from '../hooks/use-reports';
import { useSensitiveCommand } from '../hooks/use-sensitive-command';
import type { Period } from '../period';
import { CommandAlert } from './command-alert';
import { ConfirmDialog } from './confirm-dialog';
import { QueryState } from './query-state';
import { ReopenDayDialog } from './reopen-day-dialog';
import { SectionCard } from './section-card';

/**
 * The cash days of the period (§8.3): an operationally closed day is closed financially once its closings are
 * approved (the server checks); a closed day is reopened with a reason and an approver (D40).
 */
export function CashDays({ storeId, period }: { storeId: string; period: Period }) {
  const session = useSession();
  const permissions = session.data?.permissions;
  const lines = useCashReconciliation(storeId, period);
  const close = useSensitiveCommand(storeId);
  const [toClose, setToClose] = useState<CashDay | null>(null);
  const [toReopen, setToReopen] = useState<CashDay | null>(null);
  const days = cashDays(lines.data ?? []);
  const actions = {
    canClose: (day: CashDay) => day.status === 'OPERATIONALLY_CLOSED' && holds(permissions, 'CASH_CLOSING_APPROVE', storeId),
    canReopen: (day: CashDay) =>
      day.status !== 'OPEN' && holds(permissions, day.status === 'FINANCIALLY_CLOSED' ? 'CASH_DAY_REOPEN' : 'CASH_DAY_CLOSE', storeId),
    closing: close.pending,
    onClose: setToClose,
    onReopen: setToReopen,
  };

  return (
    <SectionCard title="Dias de caixa" description="O fecho financeiro do dia pede todos os fechos das caixas aprovados.">
      {close.failure && <CommandAlert {...close.failure} />}
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
            {days.map((day) => (
              <DayRow key={day.id} day={day} {...actions} />
            ))}
          </TableBody>
        </Table>
      </QueryState>
      <ConfirmDialog
        target={toClose}
        title={(day) => `Fecho financeiro do dia ${formatBusinessDate(day?.businessDate)}?`}
        description="O dia fica fechado para a contabilidade. Para o corrigir depois, é preciso reabri-lo com um aprovador."
        action="Fechar o dia"
        onCancel={() => setToClose(null)}
        onConfirm={(day) =>
          close.send({ type: 'financialClose', dayId: day.id, label: formatBusinessDate(day.businessDate), idempotencyKey: crypto.randomUUID() })
        }
      />
      <ReopenDayDialog day={toReopen} onClose={() => setToReopen(null)} />
      {close.dialog}
    </SectionCard>
  );
}

type DayRowProps = {
  day: CashDay;
  canClose: (day: CashDay) => boolean;
  canReopen: (day: CashDay) => boolean;
  closing: boolean;
  onClose: (day: CashDay) => void;
  onReopen: (day: CashDay) => void;
};

function DayRow({ day, canClose, canReopen, closing, onClose, onReopen }: DayRowProps) {
  const date = formatBusinessDate(day.businessDate);
  return (
    <TableRow>
      <TableCell className="font-medium tabular-nums">{date}</TableCell>
      <TableCell>
        <StatusBadge status={day.status} statuses={dayStatuses(day.status)} />
      </TableCell>
      <TableCell>
        <div className="flex justify-end gap-2">
          {canClose(day) && (
            <Button size="sm" aria-label={`Fecho financeiro do dia ${date}`} disabled={closing} onClick={() => onClose(day)}>
              <Lock aria-hidden="true" />
              Fecho financeiro
            </Button>
          )}
          {canReopen(day) && (
            <Button size="sm" variant="outline" aria-label={`Reabrir o dia ${date}`} onClick={() => onReopen(day)}>
              <LockOpen aria-hidden="true" />
              Reabrir
            </Button>
          )}
        </div>
      </TableCell>
    </TableRow>
  );
}
