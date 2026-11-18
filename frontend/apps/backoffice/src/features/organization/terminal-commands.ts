import type { ApiClient } from '@restaurantpro/api-client';
import { z } from 'zod';
import type { PendingCommand } from '../../lib/step-up';
import type { EnrollmentCodeResponse, TerminalCreateRequest, TerminalSummary } from './types';

/**
 * The terminal commands (stories 13, 14, 16), as values: the page sends them, a step-up stores them (FC §6), and the
 * resume page reads them back and sends them again through the same functions. Create and revoke carry the
 * Idempotency-Key the contract requires, one per intent (FC §8), so a retry or a resume replays the stored answer. The
 * code issue carries none (ADR 0035: its answer is the one-time code, never stored for a replay); repeating it only
 * replaces the code nobody saw.
 */
export type TerminalCommand =
  | { type: 'create'; body: TerminalCreateRequest; idempotencyKey: string }
  | { type: 'issueCode'; terminalId: string; terminalName: string }
  | { type: 'revoke'; terminalId: string; terminalName: string; idempotencyKey: string };

const BASE = '/api/v1/backoffice/terminals';
const UUID = '[0-9a-fA-F-]{36}';
const ISSUE_PATH = new RegExp(`^${BASE}/(${UUID})/enrollment-code$`);
const REVOKE_PATH = new RegExp(`^${BASE}/(${UUID})/revoke$`);

const createBody = z.object({
  storeId: z.uuid(),
  code: z.string().min(1).max(20),
  name: z.string().min(1).max(120),
  kind: z.literal('POS'),
});

export function createTerminal(api: ApiClient, body: TerminalCreateRequest, idempotencyKey: string) {
  return api
    .POST(BASE, { body, params: { header: { 'Idempotency-Key': idempotencyKey } } })
    .then(({ data }) => data as TerminalSummary);
}

export function issueEnrolmentCode(api: ApiClient, terminalId: string) {
  return api
    .POST(`${BASE}/{id}/enrollment-code`, { params: { path: { id: terminalId } } })
    .then(({ data }) => data as EnrollmentCodeResponse);
}

export function revokeTerminal(api: ApiClient, terminalId: string, idempotencyKey: string) {
  return api
    .POST(`${BASE}/{id}/revoke`, { params: { path: { id: terminalId }, header: { 'Idempotency-Key': idempotencyKey } } })
    .then(({ data }) => data as TerminalSummary);
}

/**
 * What a step-up keeps of a command: the request and its key, never a secret (none of these requests carries one, and
 * a key is not one).
 */
export function toPendingCommand(command: TerminalCommand, returnTo: string, tenantId: string): Omit<PendingCommand, 'savedAt'> {
  const common = { method: 'POST' as const, returnTo, tenantId };
  switch (command.type) {
    case 'create':
      return { ...common, path: BASE, body: command.body, idempotencyKey: command.idempotencyKey, label: command.body.name };
    case 'issueCode':
      return {
        ...common,
        path: `${BASE}/${command.terminalId}/enrollment-code`,
        body: null,
        idempotencyKey: null,
        label: command.terminalName,
      };
    case 'revoke':
      return {
        ...common,
        path: `${BASE}/${command.terminalId}/revoke`,
        body: null,
        idempotencyKey: command.idempotencyKey,
        label: command.terminalName,
      };
  }
}

/**
 * The terminal command a stored request is, or undefined when it is none of them: a create or revoke must come with
 * its key (resuming it under another would not be the same intent), and a code issue without one.
 */
export function fromPendingCommand(pending: PendingCommand): TerminalCommand | undefined {
  if (pending.method !== 'POST') return undefined;
  const key = pending.idempotencyKey;
  if (pending.path === BASE) {
    const body = createBody.safeParse(pending.body);
    return body.success && key ? { type: 'create', body: body.data, idempotencyKey: key } : undefined;
  }
  const issue = ISSUE_PATH.exec(pending.path);
  if (issue) return key ? undefined : { type: 'issueCode', terminalId: issue[1]!, terminalName: pending.label };
  const revoke = REVOKE_PATH.exec(pending.path);
  if (revoke && key) return { type: 'revoke', terminalId: revoke[1]!, terminalName: pending.label, idempotencyKey: key };
  return undefined;
}
