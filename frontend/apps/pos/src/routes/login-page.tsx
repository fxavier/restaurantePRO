import { Navigate } from 'react-router';
import { LoginScreen } from '../features/identity/components/login-screen';
import { useEmployee, useSessionEndedBy } from '../lib/auth/use-employee';
import { PosScreen } from './pos-screen';

/** `/login` (FC §4): the PIN pad; with a POS session, on to the tables. */
export function LoginPage() {
  const employee = useEmployee();
  const endedBy = useSessionEndedBy();
  if (employee) return <Navigate to="/tables" replace />;
  return (
    <PosScreen>
      <LoginScreen endedBy={endedBy} />
    </PosScreen>
  );
}
