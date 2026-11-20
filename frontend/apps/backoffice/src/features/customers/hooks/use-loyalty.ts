import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useApi } from '../../../lib/api';
import { sendLoyaltyCommand, type LoyaltyCommand } from '../loyalty-commands';
import type { LoyaltyBalance, LoyaltyStatement } from '../types';
import { customerKey } from './use-customer-queries';

const loyaltyKey = (customerId: string) => [...customerKey(customerId), 'loyalty'] as const;

/** Whether the customer joined, the points and the account's state, as the server computed them. */
export function useLoyalty(customerId: string) {
  const api = useApi();
  return useQuery({
    queryKey: loyaltyKey(customerId),
    queryFn: async (): Promise<LoyaltyBalance> =>
      (await api.GET('/api/v1/backoffice/loyalty/accounts/{customerId}', { params: { path: { customerId } } })).data!,
  });
}

/** The points' movements from the start until now (the server's defaults), each with the balance after it. */
export function useLoyaltyStatement(customerId: string) {
  const api = useApi();
  return useQuery({
    queryKey: [...loyaltyKey(customerId), 'statement'],
    queryFn: async (): Promise<LoyaltyStatement> =>
      (await api.GET('/api/v1/backoffice/loyalty/accounts/{customerId}/statement', { params: { path: { customerId } } }))
        .data!,
  });
}

type Kind = Exclude<LoyaltyCommand['kind'], 'adjust'>;
type BodyOf<K extends Kind> = Extract<LoyaltyCommand, { kind: K }>['body'];

/** One kind of loyalty command; never retried by itself (FC §8); the account is read again whatever the outcome. */
export function useLoyaltyCommand<K extends Kind>(customerId: string, kind: K) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: BodyOf<K>; idempotencyKey: string }) =>
      sendLoyaltyCommand(api, customerId, { kind, body } as LoyaltyCommand, idempotencyKey),
    onSettled: () => queryClient.invalidateQueries({ queryKey: loyaltyKey(customerId) }),
  });
}

/** A manual adjustment of points in the name of `storeId` (D42: authorised in that store). */
export function useAdjustPoints(customerId: string, storeId: string) {
  const api = useApi();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ body, idempotencyKey }: { body: { points: number; reason: string }; idempotencyKey: string }) =>
      sendLoyaltyCommand(api, customerId, { kind: 'adjust', body: { ...body, storeId } }, idempotencyKey),
    onSettled: () => queryClient.invalidateQueries({ queryKey: loyaltyKey(customerId) }),
  });
}
