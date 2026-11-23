import { useSearchParams } from 'react-router';
import { useSession } from '../../identity';
import { holds } from '../finance-commands';

export const FINANCE_TABS = ['resumo', 'fechos', 'sessoes', 'reconciliacao', 'fiscal', 'meios', 'auditoria'] as const;
export type FinanceTab = (typeof FINANCE_TABS)[number];

/**
 * The permissions that read each tab, any one of them enough (backend `access.Permission`): the reports need
 * REPORT_VIEW; the closings list is requireAny(CASH_VIEW, CASH_CLOSING_APPROVE); sessions CASH_VIEW; the cash
 * reconciliation CASH_VIEW and the in-doubt list RECONCILIATION_VIEW; the documents FISCAL_DOCUMENT_VIEW and the series
 * FISCAL_MANAGE; the payment methods PAYMENT_METHOD_MANAGE; the audit trail AUDIT_VIEW.
 */
const TAB_PERMISSIONS: Record<FinanceTab, readonly string[]> = {
  resumo: ['REPORT_VIEW'],
  fechos: ['CASH_VIEW', 'CASH_CLOSING_APPROVE'],
  sessoes: ['CASH_VIEW'],
  reconciliacao: ['CASH_VIEW', 'RECONCILIATION_VIEW'],
  fiscal: ['FISCAL_DOCUMENT_VIEW', 'FISCAL_MANAGE'],
  meios: ['PAYMENT_METHOD_MANAGE'],
  auditoria: ['AUDIT_VIEW'],
};

const PARAM = 'tab';

function isTab(value: string | null): value is FinanceTab {
  return (FINANCE_TABS as readonly (string | null)[]).includes(value);
}

/**
 * The open tab is the `?tab` search parameter, beside `?storeId`, so a reload, a shared link or a step-up keeps it.
 * Without one, the first tab the user may open in the store (a convenience, the server decides, §10); with none of
 * them, the first tab and its denial.
 */
export function useFinanceTab(storeId: string) {
  const [params, setParams] = useSearchParams();
  const permissions = useSession().data?.permissions;
  const value = params.get(PARAM);
  const landing =
    FINANCE_TABS.find((tab) => TAB_PERMISSIONS[tab].some((permission) => holds(permissions, permission, storeId))) ??
    FINANCE_TABS[0];
  const tab: FinanceTab = isTab(value) ? value : landing;
  const selectTab = (next: string) =>
    setParams(
      (previous) => {
        const updated = new URLSearchParams(previous);
        updated.set(PARAM, next);
        return updated;
      },
      { replace: true },
    );
  return { tab, selectTab };
}
