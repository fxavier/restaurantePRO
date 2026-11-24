import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useEmployee } from '../../../lib/auth/use-employee';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { holds } from '../cash';
import {
  type RegisterSummary,
  type SessionDetails,
  useCurrentDay,
  useCurrentSession,
  useRegisters,
} from '../hooks/use-cash';
import { DayClose } from './day-close';
import { OpenSession } from './open-session';
import { OpenSessionForm } from './open-session-form';
import { RegisterClosing } from './register-closing';

/**
 * `/cash` (§4; light reference, as on `/tables`): the operator's cash session, from opening with a float to the blind
 * count, the register's closing and the day's operational close. Every amount and state is the server's.
 */
export function CashScreen() {
  const employee = useEmployee();
  const allowed = holds(employee, 'CASH_OPERATE');
  const session = useCurrentSession(allowed);
  const registers = useRegisters(allowed);

  if (!allowed) return <p className="py-6 text-muted-foreground">Sem permissão para operar a caixa.</p>;
  const failed = session.error ?? registers.error;
  const reload = () => void Promise.all([session.refetch(), registers.refetch()]);
  const loaded = session.data !== undefined && registers.data !== undefined;
  // A failed re-read (after a command with no answer, say) keeps what is shown mounted: swapping it for the load error
  // would drop a kept intent and its Idempotency-Key, and the next attempt would be a second command (FC §8).
  if (failed && !loaded) {
    return (
      <div className="flex flex-col items-center gap-3 rounded-2xl border bg-card p-6 text-center">
        <p role="alert">{loadError(failed, 'a caixa')}</p>
        <Button variant="outline" size="touch" className="rounded-xl bg-card" onClick={reload}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  if (session.data === undefined || !registers.data) {
    return (
      <div role="status" aria-label="A carregar a caixa…" className="flex flex-col gap-4">
        <Skeleton className="h-40 rounded-2xl" />
        <Skeleton className="h-72 rounded-2xl" />
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      {failed && (
        <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-warning-border bg-warning-tint p-4 text-warning-text">
          <p>{refreshError(failed)}</p>
          <Button variant="outline" size="touch" className="rounded-xl bg-card" onClick={reload}>
            Atualizar
          </Button>
        </div>
      )}
      <CashContent session={session.data} registers={registers.data} />
    </div>
  );
}

/** What is shown stays, but may be out of date: the server could not be read again. */
function refreshError(error: Error): string {
  if (error instanceof ApiError && isOffline(error)) {
    return 'Sem ligação — não foi possível atualizar a caixa. O que vê pode estar desatualizado.';
  }
  return 'Não foi possível atualizar a caixa. O que vê pode estar desatualizado.';
}

function CashContent({ session, registers }: { session: SessionDetails | null; registers: RegisterSummary[] }) {
  const employee = useEmployee();
  const day = useCurrentDay(true);
  const dayCard = day.data?.id ? (
    <DayClose day={{ ...day.data, id: day.data.id }} canClose={holds(employee, 'CASH_DAY_CLOSE')} />
  ) : day.error ? (
    <p role="alert" className="rounded-2xl border bg-card p-4">
      {loadError(day.error, 'o dia comercial')}
    </p>
  ) : null;
  if (session) {
    const register = registers.find((candidate) => candidate.id === session.registerId);
    return (
      <OpenSession session={session} register={register}>
        {dayCard}
      </OpenSession>
    );
  }
  return (
    <div className="grid grid-cols-1 items-start gap-4 lg:grid-cols-2">
      <OpenSessionForm registers={registers} />
      <div className="flex flex-col gap-4">
        {day.data?.id && <RegisterClosing registers={registers} dayId={day.data.id} />}
        {dayCard}
      </div>
    </div>
  );
}

/** @param what what could not be read ("a caixa", "o dia comercial") */
function loadError(error: Error, what: string): string {
  if (!(error instanceof ApiError)) return `Não foi possível carregar ${what}. A resposta do servidor está incompleta.`;
  if (isOffline(error)) return `Sem ligação — não foi possível carregar ${what}.`;
  return `Não foi possível carregar ${what}. ${errorMessage(error)}`;
}
