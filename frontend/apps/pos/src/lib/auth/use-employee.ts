import { useSyncExternalStore } from 'react';
import { useServices } from '../services';
import type { Employee, EndReason } from './pos-session';

/** The logged-in employee, or none; re-renders on login, PIN change and logout. */
export function useEmployee(): Employee | undefined {
  const { session } = useServices();
  return useSyncExternalStore(session.subscribe, session.employee);
}

export function useSessionEndedBy(): EndReason | undefined {
  const { session } = useServices();
  return useSyncExternalStore(session.subscribe, session.endedBy);
}
