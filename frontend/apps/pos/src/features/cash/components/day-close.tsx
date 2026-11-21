import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  ConfirmButton,
  StatusBadge,
  type StatusMap,
} from '@restaurantpro/ui';
import { CalendarCheck, CalendarClock, Lock, ShieldCheck, TriangleAlert } from 'lucide-react';
import { useId, useState } from 'react';
import { useCommandsBlocked } from '../../../lib/live/use-connection';
import { businessDay } from '../format';
import type { CurrentDay } from '../hooks/use-cash';
import { useCloseDay } from '../hooks/use-cash-commands';
import { cashRefusal } from '../messages';
import { CommandFeedback } from './command-feedback';

type DayStatus = 'OPEN' | 'OPERATIONALLY_CLOSED' | 'FINANCIALLY_CLOSED';

const STATUSES: StatusMap<DayStatus> = {
  OPEN: { label: 'Aberto', icon: CalendarClock, tone: 'success' },
  OPERATIONALLY_CLOSED: { label: 'Fechado (operacional)', icon: CalendarCheck, tone: 'warning' },
  FINANCIALLY_CLOSED: { label: 'Fechado (financeiro)', icon: Lock, tone: 'neutral' },
};

type Props = {
  day: CurrentDay & { id: string };
  /** `CASH_DAY_CLOSE`: the close is offered only to whoever may do it (the backend enforces). */
  canClose: boolean;
};

/**
 * The business day and its operational close (§8.3: OPEN → OPERATIONALLY_CLOSED), confirmed first: the server refuses
 * it while a session, an order, a fiscal draft or a payment in doubt is still open.
 */
export function DayClose({ day, canClose }: Props) {
  const title = useId();
  const [confirming, setConfirming] = useState(false);
  const close = useCloseDay(day.id);
  const blocked = useCommandsBlocked();
  const date = day.businessDate ? businessDay(day.businessDate) : '';
  const status = day.status && day.status in STATUSES ? (day.status as DayStatus) : undefined;
  const awaitingRetry = !close.pending && close.intent !== undefined;

  return (
    <section aria-labelledby={title} className="flex flex-col gap-4 rounded-xl border bg-card p-4 lg:p-6">
      <h3 id={title} className="flex items-center gap-2 text-xl font-semibold">
        <ShieldCheck aria-hidden="true" className="size-6 text-primary-hover" />
        Fecho operacional do dia
      </h3>
      <div className="flex flex-wrap items-center gap-3">
        <span className="text-muted-foreground">Dia comercial:</span>
        <span className="text-lg font-semibold">{date}</span>
        {status ? <StatusBadge status={status} statuses={STATUSES} /> : <span>{day.status}</span>}
      </div>
      {(day.earlierOpenDays ?? []).map((earlier) => (
        <p key={earlier.id} className="flex items-center gap-2 font-medium text-error">
          <TriangleAlert aria-hidden="true" className="size-5 shrink-0" />
          {`O dia ${businessDay(earlier.businessDate ?? '')} ainda está aberto.`}
        </p>
      ))}
      <p className="text-muted-foreground">
        O fecho operacional encerra o dia na loja: só é aceite sem sessões de caixa, pedidos ou pagamentos em aberto.
      </p>
      {canClose && status === 'OPEN' && (
        <>
          <CommandFeedback blocked={blocked} error={close.error && cashRefusal(close.error, 'o fecho do dia')} />
          <ConfirmButton
            type="button"
            size="touch-primary"
            pending={close.pending}
            disabled={blocked}
            onClick={() => (awaitingRetry ? close.retry() : setConfirming(true))}
          >
            {awaitingRetry ? 'Tentar novamente' : 'Fechar dia'}
          </ConfirmButton>
          <AlertDialog open={confirming} onOpenChange={setConfirming}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>{`Fechar o dia ${date}?`}</AlertDialogTitle>
                <AlertDialogDescription>
                  Depois do fecho operacional não se abrem mais sessões de caixa neste dia.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel size="touch">Voltar</AlertDialogCancel>
                <AlertDialogAction size="touch" onClick={() => close.start(day.version ?? 0, undefined)}>
                  Confirmar fecho do dia
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      )}
    </section>
  );
}
