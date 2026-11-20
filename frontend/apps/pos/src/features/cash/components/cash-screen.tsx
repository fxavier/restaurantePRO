import { ApiError } from '@restaurantpro/api-client';
import { Button, Skeleton } from '@restaurantpro/ui';
import { useEmployee } from '../../../lib/auth/use-employee';
import { errorMessage, isOffline } from '../../../lib/error-messages';
import { holds } from '../cash';
import { type RegisterSummary, type SessionDetails, useCurrentSession, useRegisters } from '../hooks/use-cash';
import { OpenSession } from './open-session';
import { OpenSessionForm } from './open-session-form';

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
        <p role="alert">{loadError(failed)}</p>
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
  if (!session) return <OpenSessionForm registers={registers} />;
  const register = registers.find((candidate) => candidate.id === session.registerId);
  return <OpenSession session={session} register={register} />;
}

function loadError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Não foi possível carregar a caixa. A resposta do servidor está incompleta.';
  if (isOffline(error)) return 'Sem ligação — não foi possível carregar a caixa.';
  return `Não foi possível carregar a caixa. ${errorMessage(error)}`;
}
