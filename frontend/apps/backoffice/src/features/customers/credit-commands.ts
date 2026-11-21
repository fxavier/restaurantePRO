import type { ApiClient } from '@restaurantpro/api-client';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';
import type { CreditView } from './types';

/**
 * The BackOffice credit commands (§5, D73): change the limit (the first change opens the account), block, unblock.
 * Each carries the Idempotency-Key of its intent (FC §8); a 409 IDEMPOTENCY_IN_PROGRESS waits and resends the same.
 * Credit payments are not here: they are taken at the POS only.
 */
export type CreditCommand =
  | { kind: 'limit'; body: { newLimit: string; reason: string } }
  | { kind: 'block'; body: { reason: string } }
  | { kind: 'unblock'; body: { reason?: string } };

export function sendCreditCommand(api: ApiClient, customerId: string, command: CreditCommand, idempotencyKey: string) {
  const params = { path: { id: customerId }, header: { 'Idempotency-Key': idempotencyKey } };
  const send = () => {
    switch (command.kind) {
      case 'limit':
        return api.POST('/api/v1/backoffice/customers/{id}/credit-limit-changes', { params, body: command.body });
      case 'block':
        return api.POST('/api/v1/backoffice/customers/{id}/credit-block', { params, body: command.body });
      case 'unblock':
        return api.POST('/api/v1/backoffice/customers/{id}/credit-unblock', { params, body: command.body });
    }
  };
  return sendWaitingWhileInProgress(send).then(({ data }) => data as CreditView);
}
