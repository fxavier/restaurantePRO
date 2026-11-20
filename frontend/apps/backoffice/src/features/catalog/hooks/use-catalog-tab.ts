import { useSearchParams } from 'react-router';

export const CATALOG_TABS = ['artigos', 'familias', 'precos', 'versoes'] as const;
export type CatalogTab = (typeof CATALOG_TABS)[number];

const PARAM = 'tab';

function isTab(value: string | null): value is CatalogTab {
  return (CATALOG_TABS as readonly (string | null)[]).includes(value);
}

/** The open tab is the `?tab` search parameter, beside `?storeId`, so a reload or a shared link keeps it. */
export function useCatalogTab() {
  const [params, setParams] = useSearchParams();
  const value = params.get(PARAM);
  const tab: CatalogTab = isTab(value) ? value : 'artigos';
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
