import { useEmployee, useStoreTimeZone } from '../../../lib/auth/use-employee';
import { useCurrentSession, useRegisters } from '../hooks/use-cash';
import { OpenSessionForm } from './open-session-form';
import { SessionCard } from './session-card';

/**
 * `/cash` (§4, Stitch "Caixa e Fecho de Turno"): the operator's cash session, from opening with a float to the blind
 * count, the register's closing and the day's operational close. Every amount and state is the server's.
 */
export function CashScreen() {
  const employee = useEmployee();
  const timeZone = useStoreTimeZone();
  const session = useCurrentSession();
  const registers = useRegisters();

  if (session.data === undefined || !registers.data) return null;
  if (session.data === null) return <OpenSessionForm registers={registers.data} />;
  const register = registers.data.find((candidate) => candidate.id === session.data?.registerId);
  return (
    <SessionCard session={session.data} register={register} operator={employee?.name ?? ''} timeZone={timeZone} />
  );
}
