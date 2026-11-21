import type { ApiClient } from '@restaurantpro/api-client';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';

/**
 * The BackOffice loyalty account commands (§5, §8.4): suspend and resume (LOYALTY_ENROLL) and a manual adjustment
 * in a store's name (LOYALTY_ADJUST there). Each carries the Idempotency-Key of its intent (FC §8). Joining the
 * programme is a POS command, so there is none here.
 */
export type LoyaltyCommand =
  | { kind: 'suspend'; body: { reason: string } }
  | { kind: 'resume'; body: { reason: string } }
  | { kind: 'adjust'; body: { points: number; reason: string; storeId: string } };

export function sendLoyaltyCommand(api: ApiClient, customerId: string, command: LoyaltyCommand, idempotencyKey: string) {
  const params = { path: { customerId }, header: { 'Idempotency-Key': idempotencyKey } };
  const send = () => {
    switch (command.kind) {
      case 'suspend':
        return api.POST('/api/v1/backoffice/loyalty/accounts/{customerId}/suspend', { params, body: command.body });
      case 'resume':
        return api.POST('/api/v1/backoffice/loyalty/accounts/{customerId}/resume', { params, body: command.body });
      case 'adjust':
        return api.POST('/api/v1/backoffice/loyalty/accounts/{customerId}/adjustments', { params, body: command.body });
    }
  };
  return sendWaitingWhileInProgress(send).then(({ data }) => data);
}
