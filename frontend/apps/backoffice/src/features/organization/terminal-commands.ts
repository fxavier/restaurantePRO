import type { ApiClient } from '@restaurantpro/api-client';
import { z } from 'zod';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';
import type { PendingCommand } from '../../lib/step-up';
import type { EnrollmentCodeResponse, TerminalCreateRequest, TerminalSummary } from './types';

/**
 * The terminal commands (stories 13, 14, 16), as values: the page sends them, a step-up stores them (FC §6), and the
 * resume page reads them back and sends them again through the same functions. The contract declares no
 * Idempotency-Key on any of them, so none is sent (FC §8): a repeated create is refused as a taken code (409), a
 * repeated code issue replaces the code nobody saw, and a repeated revoke is refused as already revoked.
 */
export type TerminalCommand =
  | { type: 'create'; body: TerminalCreateRequest }
  | { type: 'issueCode'; terminalId: string; terminalName: string }
  | { type: 'revoke'; terminalId: string; terminalName: string };

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

/** A 409 IDEMPOTENCY_IN_PROGRESS waits and is resent as it was (FE-F25). */
export function createTerminal(api: ApiClient, body: TerminalCreateRequest) {
  return sendWaitingWhileInProgress(() => api.POST(BASE, { body })).then(({ data }) => data as TerminalSummary);
}

export function issueEnrolmentCode(api: ApiClient, terminalId: string) {
  return api
    .POST(`${BASE}/{id}/enrollment-code`, { params: { path: { id: terminalId } } })
    .then(({ data }) => data as EnrollmentCodeResponse);
}

export function revokeTerminal(api: ApiClient, terminalId: string) {
  return api.POST(`${BASE}/{id}/revoke`, { params: { path: { id: terminalId } } }).then(({ data }) => data as TerminalSummary);
}

/** What a step-up keeps of a command: the request, never a secret (none of these requests carries one). */
export function toPendingCommand(command: TerminalCommand, returnTo: string, tenantId: string): Omit<PendingCommand, 'savedAt'> {
  const common = { method: 'POST' as const, idempotencyKey: null, returnTo, tenantId };
  switch (command.type) {
    case 'create':
      return { ...common, path: BASE, body: command.body, label: command.body.name };
    case 'issueCode':
      return { ...common, path: `${BASE}/${command.terminalId}/enrollment-code`, body: null, label: command.terminalName };
    case 'revoke':
      return { ...common, path: `${BASE}/${command.terminalId}/revoke`, body: null, label: command.terminalName };
  }
}

/** The terminal command a stored request is, or undefined when it is none of them. */
export function fromPendingCommand(pending: PendingCommand): TerminalCommand | undefined {
  if (pending.method !== 'POST') return undefined;
  if (pending.path === BASE) {
    const body = createBody.safeParse(pending.body);
    return body.success ? { type: 'create', body: body.data } : undefined;
  }
  const issue = ISSUE_PATH.exec(pending.path);
  if (issue) return { type: 'issueCode', terminalId: issue[1]!, terminalName: pending.label };
  const revoke = REVOKE_PATH.exec(pending.path);
  if (revoke) return { type: 'revoke', terminalId: revoke[1]!, terminalName: pending.label };
  return undefined;
}
