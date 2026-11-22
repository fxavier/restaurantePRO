import type { components } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';

type Schemas = components['schemas'];
export type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];

/** The search's shortest text (the backend's D72): a name of 3 characters; a phone of 4 digits is longer anyway. */
export const MIN_SEARCH = 3;

/**
 * `GET /api/v1/pos/customers?q=`: at most a few candidates, by name or phone suffix, never a selection (§5). Runs
 * for the text the waiter submitted, not on every keystroke.
 */
export function useCustomerSearch(text: string | undefined) {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'customers', 'search', text],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/customers', { params: { query: { q: text } }, signal })).data ?? [],
    enabled: text !== undefined,
  });
}

/** `GET /api/v1/pos/customers/{id}`: the chosen customer, with the saved addresses a delivery may go to. */
export function useCustomer(customerId: string | undefined) {
  const { api } = useServices();
  return useQuery({
    queryKey: ['pos', 'customers', customerId],
    queryFn: async ({ signal }) =>
      (await api.GET('/api/v1/pos/customers/{id}', { params: { path: { id: customerId ?? '' } }, signal })).data,
    enabled: customerId !== undefined,
  });
}

/** A "Criar cliente" intent, owned by the dialog it may hold open. */
export type CustomerCreation = ReturnType<typeof useCreateCustomer>;

/** `POST /api/v1/pos/customers`: one intent per "Criar cliente", resolving with the new customer's id. */
export function useCreateCustomer({ onCreated }: { onCreated: (customerId: string) => void }) {
  const { api } = useServices();
  return useIntent(
    async (body: CustomerCreateRequest, key: string) =>
      (await api.POST('/api/v1/pos/customers', { params: { header: { 'Idempotency-Key': key } }, body })).data,
    {
      onSuccess(customer) {
        if (customer?.id) onCreated(customer.id);
      },
    },
  );
}
