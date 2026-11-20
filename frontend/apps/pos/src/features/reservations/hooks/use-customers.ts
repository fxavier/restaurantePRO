import type { components } from '@restaurantpro/api-client';
import { useQuery } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';

type Schemas = components['schemas'];
type CustomerCreateRequest = Schemas['com.xavier.backend.customers.model.dto.CustomerCreateRequest'];
type CustomerDetails = Schemas['com.xavier.backend.customers.model.dto.CustomerDetails'];

/** A customer as the picker shows it. */
export type CustomerMatch = { id: string; name: string; phones: string[] };

const CUSTOMERS_KEY = ['pos', 'customers'] as const;

/** `GET /api/v1/pos/customers?q=`: the search the host asked for, none while `q` is empty. */
export function useCustomerSearch(q: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: [...CUSTOMERS_KEY, 'search', q],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/customers', { params: { query: { q } }, signal })).data,
    enabled: q !== '',
    select: (candidates): CustomerMatch[] =>
      (candidates ?? []).flatMap(({ id, name, phones }) => (id && name ? [{ id, name, phones: phones ?? [] }] : [])),
  });
}

/** `GET /api/v1/pos/customers/{id}`: the linked customer's name. */
export function useCustomer(id: string) {
  const { api } = useServices();
  return useQuery({
    queryKey: [...CUSTOMERS_KEY, id],
    queryFn: async ({ signal }) => (await api.GET('/api/v1/pos/customers/{id}', { params: { path: { id } }, signal })).data,
  });
}

/** `POST /api/v1/pos/customers`: one intent per "Criar ficha de cliente". */
export function useCreateCustomer({ onCreated }: { onCreated: (customer: CustomerDetails) => void }) {
  const { api } = useServices();
  return useIntent(
    async (body: CustomerCreateRequest, key: string) =>
      (await api.POST('/api/v1/pos/customers', { params: { header: { 'Idempotency-Key': key } }, body })).data,
    {
      onSuccess(customer) {
        if (customer) onCreated(customer);
      },
    },
  );
}
