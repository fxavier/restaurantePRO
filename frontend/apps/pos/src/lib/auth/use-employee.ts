import { useSyncExternalStore } from 'react';
import { useServices } from '../services';
import type { Employee, EndReason } from './pos-session';

/** The logged-in employee, or none; re-renders on login, PIN change and logout. */
export function useEmployee(): Employee | undefined {
  const { session } = useServices();
  return useSyncExternalStore(session.subscribe, session.employee);
}

/** The zone the POS shows times in: the session's store's (FC §12). Only under a session (`RequireSession`). */
export function useStoreTimeZone(): string {
  const employee = useEmployee();
  if (!employee) throw new Error('No POS session');
  return employee.storeTimeZone;
}

export function useSessionEndedBy(): EndReason | undefined {
  const { session } = useServices();
  return useSyncExternalStore(session.subscribe, session.endedBy);
}
