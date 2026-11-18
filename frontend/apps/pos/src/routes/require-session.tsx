import { useCallback } from 'react';
import { Navigate, Outlet } from 'react-router';
import { ChangePinScreen } from '../features/identity/components/change-pin-screen';
import { useEmployee } from '../lib/auth/use-employee';
import { useEndSession } from '../lib/auth/use-end-session';
import { useIdle } from '../lib/auth/use-idle';
import { LiveUpdates } from '../lib/live/live-updates';
import { PosScreen } from './pos-screen';

/**
 * The guard of every route behind the PIN pad (FC §4, §6): no POS session → `/login`; a PIN a manager reset → the
 * PIN change, before anything else; 90 s without input → logout. Past those, the session's change stream runs.
 */
export function RequireSession() {
  const employee = useEmployee();
  if (!employee) return <Navigate to="/login" replace />;
  return (
    <>
      <IdleLogout />
      {employee.mustChangePin ? (
        <PosScreen>
          <ChangePinScreen name={employee.name} />
        </PosScreen>
      ) : (
        <LiveUpdates>
          <Outlet />
        </LiveUpdates>
      )}
    </>
  );
}

function IdleLogout() {
  const endSession = useEndSession();
  useIdle(useCallback(() => endSession('idle'), [endSession]));
  return null;
}
