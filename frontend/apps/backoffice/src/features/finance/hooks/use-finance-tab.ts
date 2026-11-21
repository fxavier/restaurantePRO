import { useSearchParams } from 'react-router';

export const FINANCE_TABS = ['resumo', 'fechos', 'reconciliacao', 'fiscal', 'meios', 'auditoria'] as const;
export type FinanceTab = (typeof FINANCE_TABS)[number];

const PARAM = 'tab';

function isTab(value: string | null): value is FinanceTab {
  return (FINANCE_TABS as readonly (string | null)[]).includes(value);
}

/** The open tab is the `?tab` search parameter, beside `?storeId`, so a reload, a shared link or a step-up keeps it. */
export function useFinanceTab() {
  const [params, setParams] = useSearchParams();
  const value = params.get(PARAM);
  const tab: FinanceTab = isTab(value) ? value : 'resumo';
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
