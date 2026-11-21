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
 * `/cash` (§4, Stitch "Caixa e Fecho de Turno"): the operator's cash session, from opening with a float to the blind
 * count, the register's closing and the day's operational close. Every amount and state is the server's.
 */
export function CashScreen() {
  const employee = useEmployee();
  const allowed = holds(employee, 'CASH_OPERATE');
  const session = useCurrentSession(allowed);
  const registers = useRegisters(allowed);

  if (!allowed) return <p className="py-6 text-muted-foreground">Sem permissão para operar a caixa.</p>;
  const failed = session.error ?? registers.error;
  if (failed) {
    return (
      <div className="flex flex-col items-center gap-3 py-6 text-center">
        <p role="alert">{loadError(failed, 'a caixa')}</p>
        <Button variant="outline" size="touch" onClick={() => void Promise.all([session.refetch(), registers.refetch()])}>
          Tentar novamente
        </Button>
      </div>
    );
  }
  if (session.data === undefined || !registers.data) {
    return (
      <div role="status" aria-label="A carregar a caixa…" className="flex flex-col gap-4">
        <Skeleton className="h-40 rounded-xl" />
        <Skeleton className="h-72 rounded-xl" />
      </div>
    );
  }
  return <CashContent session={session.data} registers={registers.data} />;
}

function CashContent({ session, registers }: { session: SessionDetails | null; registers: RegisterSummary[] }) {
  const employee = useEmployee();
  const day = useCurrentDay(true);
  const dayCard = day.data?.id ? (
    <DayClose day={{ ...day.data, id: day.data.id }} canClose={holds(employee, 'CASH_DAY_CLOSE')} />
  ) : day.error ? (
    <p role="alert" className="rounded-xl border bg-card p-4">
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
