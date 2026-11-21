import type { ApiClient } from '@restaurantpro/api-client';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';
import type {
  AddressRequest,
  AddressUpdateRequest,
  AddressView,
  CustomerCreateRequest,
  CustomerDetails,
  CustomerUpdateRequest,
  PhoneRequest,
  PhoneView,
} from './types';

/**
 * The customer commands of the BackOffice. Those whose contract requires an Idempotency-Key send the key of their
 * intent and wait while the same request is still in progress (FC §8); the edits (PUT, DELETE) take none: the version
 * guards an edit, and deleting twice deletes once.
 */
const key = (idempotencyKey: string) => ({ 'Idempotency-Key': idempotencyKey });

export function createCustomer(api: ApiClient, body: CustomerCreateRequest, idempotencyKey: string) {
  return sendWaitingWhileInProgress(() =>
    api.POST('/api/v1/backoffice/customers', { params: { header: key(idempotencyKey) }, body }),
  ).then(({ data }) => data as CustomerDetails);
}

export function updateCustomer(api: ApiClient, customerId: string, body: CustomerUpdateRequest) {
  return api
    .PUT('/api/v1/backoffice/customers/{id}', { params: { path: { id: customerId } }, body })
    .then(({ data }) => data as CustomerDetails);
}

export function addPhone(api: ApiClient, customerId: string, body: PhoneRequest, idempotencyKey: string) {
  return sendWaitingWhileInProgress(() =>
    api.POST('/api/v1/backoffice/customers/{id}/phones', {
      params: { path: { id: customerId }, header: key(idempotencyKey) },
      body,
    }),
  ).then(({ data }) => data as PhoneView);
}

export function deletePhone(api: ApiClient, customerId: string, phoneId: string) {
  return api.DELETE('/api/v1/backoffice/customers/{id}/phones/{phoneId}', {
    params: { path: { id: customerId, phoneId } },
  });
}

export function addAddress(api: ApiClient, customerId: string, body: AddressRequest, idempotencyKey: string) {
  return sendWaitingWhileInProgress(() =>
    api.POST('/api/v1/backoffice/customers/{id}/addresses', {
      params: { path: { id: customerId }, header: key(idempotencyKey) },
      body,
    }),
  ).then(({ data }) => data as AddressView);
}

export function updateAddress(api: ApiClient, customerId: string, addressId: string, body: AddressUpdateRequest) {
  return api
    .PUT('/api/v1/backoffice/customers/{id}/addresses/{addressId}', {
      params: { path: { id: customerId, addressId } },
      body,
    })
    .then(({ data }) => data as AddressView);
}
