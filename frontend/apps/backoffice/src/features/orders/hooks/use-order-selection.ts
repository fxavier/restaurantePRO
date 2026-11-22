import { useSearchParams } from 'react-router';

const PARAM = 'orderId';

/** The open order is the `?orderId` search parameter, beside `?storeId`: a reload or a shared link opens it again. */
export function useOrderSelection() {
  const [params, setParams] = useSearchParams();
  const orderId = params.get(PARAM) ?? undefined;
  const selectOrder = (id: string | undefined) =>
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      if (id) next.set(PARAM, id);
      else next.delete(PARAM);
      return next;
    });
  return { orderId, selectOrder };
}
