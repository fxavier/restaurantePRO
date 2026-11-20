import type { StatusMap } from '@restaurantpro/ui';
import { Ban, CircleCheck, CircleSlash, Clock, Lock } from 'lucide-react';
import type { EmployeeSummary, SupportGrantSummary } from '../types';

export type EmployeeState = 'ACTIVE' | 'LOCKED' | 'INACTIVE';

/** A locked PIN matters first (the employee cannot open a POS session), then whether the employee is still active. */
export function employeeState(employee: EmployeeSummary): EmployeeState {
  if (employee.locked) return 'LOCKED';
  return employee.active === false ? 'INACTIVE' : 'ACTIVE';
}

export const employeeStatuses: StatusMap<EmployeeState> = {
  ACTIVE: { label: 'Ativo', icon: CircleCheck, tone: 'success' },
  LOCKED: { label: 'Bloqueado', icon: Lock, tone: 'destructive' },
  INACTIVE: { label: 'Inativo', icon: CircleSlash, tone: 'neutral' },
};

/** An account is active or not; the same two labels as an employee's. */
export const accountStatuses: StatusMap<'ACTIVE' | 'INACTIVE'> = {
  ACTIVE: employeeStatuses.ACTIVE,
  INACTIVE: employeeStatuses.INACTIVE,
};

type GrantStatus = NonNullable<SupportGrantSummary['status']>;

export const grantStatuses: StatusMap<GrantStatus> = {
  ACTIVE: { label: 'Ativo', icon: CircleCheck, tone: 'success' },
  EXPIRED: { label: 'Expirado', icon: Clock, tone: 'neutral' },
  REVOKED: { label: 'Revogado', icon: Ban, tone: 'destructive' },
};
