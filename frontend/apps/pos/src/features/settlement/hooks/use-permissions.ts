import { useEmployee } from '../../../lib/auth/use-employee';

/** What the employee may do on a bill; for showing controls only, the backend enforces (§10). */
export type Can = { split: boolean; collect: boolean; reprint: boolean };

/** The session's authorities in its store (`PERM_X@storeId`): BILL_SPLIT, PAYMENT_COLLECT, FISCAL_DOCUMENT_REPRINT. */
export function useSettlementPermissions(): Can {
  const employee = useEmployee();
  const has = (permission: string) => employee?.permissions.includes(`PERM_${permission}@${employee.storeId}`) ?? false;
  return { split: has('BILL_SPLIT'), collect: has('PAYMENT_COLLECT'), reprint: has('FISCAL_DOCUMENT_REPRINT') };
}
