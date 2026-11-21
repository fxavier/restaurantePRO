import { useSearchParams } from 'react-router';

const PARAM = 'customerId';

/**
 * The selected customer is the `?customerId` search parameter, beside `?storeId`: a reload, a shared link or the
 * return from a step-up opens the same customer.
 */
export function useCustomerSelection() {
  const [params, setParams] = useSearchParams();
  const customerId = params.get(PARAM) ?? undefined;
  const selectCustomer = (id: string) =>
    setParams((previous) => {
      const next = new URLSearchParams(previous);
      next.set(PARAM, id);
      return next;
    });
  return { customerId, selectCustomer };
}
