import { useEmployee } from '../../../lib/auth/use-employee';

type Permission = 'RESERVATION_MANAGE' | 'CUSTOMER_VIEW' | 'CUSTOMER_MANAGE';

/** Whether the employee holds `permission` in the session's store: for showing controls only, the backend enforces. */
export function useCan(permission: Permission): boolean {
  const employee = useEmployee();
  return employee?.permissions.includes(`PERM_${permission}@${employee.storeId}`) ?? false;
}
