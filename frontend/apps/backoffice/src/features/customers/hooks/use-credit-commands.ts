import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendCreditCommand, type CreditCommand } from '../credit-commands';
import { creditKey, CUSTOMERS_KEY } from './use-customer-queries';

type Kind = CreditCommand['kind'];
type BodyOf<K extends Kind> = Extract<CreditCommand, { kind: K }>['body'];

/**
 * One kind of credit command of one customer. Mutations never retry (FC §8): the dialog resends the same body under
 * the same key. Whatever the outcome, the account, its statement and the debts are read again, so the server's
 * figures are what is shown.
 */
export function useCreditCommand<K extends Kind>(customerId: string, kind: K) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: BodyOf<K>; idempotencyKey: string }) =>
      sendCreditCommand(api, customerId, { kind, body } as CreditCommand, idempotencyKey),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: creditKey(customerId) }),
        queryClient.invalidateQueries({ queryKey: [...CUSTOMERS_KEY, 'debts'] }),
      ]),
  });
}
