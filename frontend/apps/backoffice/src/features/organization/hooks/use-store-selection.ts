import { useSearchParams } from 'react-router';

const PARAM = 'storeId';

/** The selected store is the `?storeId` search parameter, so a reload or a shared link keeps it (FC §4, gap G8). */
export function useStoreSelection() {
  const [params, setParams] = useSearchParams();
  const storeId = params.get(PARAM) ?? undefined;
  const selectStore = (id: string) =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.set(PARAM, id);
        return next;
      },
      { replace: true },
    );
  const clearStore = () =>
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.delete(PARAM);
        return next;
      },
      { replace: true },
    );
  return { storeId, selectStore, clearStore };
}

/** The search string that carries the store to another route. */
export function storeSearch(storeId: string | undefined) {
  return storeId ? `?${new URLSearchParams({ [PARAM]: storeId })}` : '';
}
