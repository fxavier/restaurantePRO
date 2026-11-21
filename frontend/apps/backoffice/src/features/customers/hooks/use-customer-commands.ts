import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { addAddress, addPhone, createCustomer, deletePhone, updateAddress, updateCustomer } from '../customer-commands';
import type { AddressRequest, AddressUpdateRequest, CustomerCreateRequest, CustomerUpdateRequest, PhoneRequest } from '../types';
import { customerKey, CUSTOMERS_KEY } from './use-customer-queries';

/** Mutations never retry (FC §8); each reads the customer again whatever the outcome, so the server's data is shown. */
function useRefreshCustomer(customerId: string) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: customerKey(customerId) });
}

export function useCreateCustomer() {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: CustomerCreateRequest; idempotencyKey: string }) =>
      createCustomer(api, body, idempotencyKey),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: [...CUSTOMERS_KEY, 'search'] }),
  });
}

/** The edit carries no key (the contract takes none); the version in the body guards it. */
export function useUpdateCustomer(customerId: string) {
  const api = useApi();
  const refresh = useRefreshCustomer(customerId);
  return useMutation({
    mutationFn: ({ body }: { body: CustomerUpdateRequest; idempotencyKey: string }) => updateCustomer(api, customerId, body),
    onSettled: refresh,
  });
}

export function useAddPhone(customerId: string) {
  const api = useApi();
  const refresh = useRefreshCustomer(customerId);
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: PhoneRequest; idempotencyKey: string }) =>
      addPhone(api, customerId, body, idempotencyKey),
    onSettled: refresh,
  });
}

export function useDeletePhone(customerId: string) {
  const api = useApi();
  const refresh = useRefreshCustomer(customerId);
  return useMutation({
    mutationFn: ({ phoneId }: { phoneId: string; label: string }) => deletePhone(api, customerId, phoneId),
    onSettled: refresh,
  });
}

export function useAddAddress(customerId: string) {
  const api = useApi();
  const refresh = useRefreshCustomer(customerId);
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: AddressRequest; idempotencyKey: string }) =>
      addAddress(api, customerId, body, idempotencyKey),
    onSettled: refresh,
  });
}

/** Like the customer edit: no key (the contract takes none); the version in the body guards it. */
export function useUpdateAddress(customerId: string, addressId: string) {
  const api = useApi();
  const refresh = useRefreshCustomer(customerId);
  return useMutation({
    mutationFn: ({ body }: { body: AddressUpdateRequest; idempotencyKey: string }) =>
      updateAddress(api, customerId, addressId, body),
    onSettled: refresh,
  });
}
