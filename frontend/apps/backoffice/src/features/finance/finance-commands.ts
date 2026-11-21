import type { ApiClient } from '@restaurantpro/api-client';
import { z } from 'zod';
import { sendWaitingWhileInProgress } from '../../lib/in-progress';
import type { PendingCommand } from '../../lib/step-up';
import type { FinanceTab } from './hooks/use-finance-tab';
import type { PaymentMethodCreateRequest, PaymentMethodUpdateRequest, SeriesCreateRequest } from './types';

/**
 * The finance commands that use a sensitive permission (D142: CASH_CLOSING_APPROVE, FISCAL_MANAGE,
 * PAYMENT_METHOD_MANAGE), as values: the screen sends them, a step-up keeps them (FC §6), and the tab they came from
 * reads them back and sends them again. Those whose contract takes an Idempotency-Key carry one per intent (FC §8), so
 * a resend or a resume is the same intent. None holds a secret: the day reopen carries an approver's PIN, so it is not
 * one of them and is never kept.
 */
export type FinanceCommand =
  | { type: 'approveClosing'; closingId: string; label: string; idempotencyKey: string }
  | { type: 'rejectClosing'; closingId: string; label: string; reason: string; idempotencyKey: string }
  | { type: 'financialClose'; dayId: string; label: string; idempotencyKey: string }
  | { type: 'createSeries'; body: SeriesCreateRequest }
  | { type: 'deactivateSeries'; seriesId: string; label: string }
  | { type: 'createMethod'; body: PaymentMethodCreateRequest }
  | { type: 'updateMethod'; methodId: string; body: PaymentMethodUpdateRequest };

const B = '/api/v1/backoffice';
const ID = '([0-9a-fA-F-]{36})';

/** The tab each command lives on: where a step-up comes back to. */
export function commandTab(command: FinanceCommand): FinanceTab {
  switch (command.type) {
    case 'approveClosing':
    case 'rejectClosing':
    case 'financialClose':
      return 'fechos';
    case 'createSeries':
    case 'deactivateSeries':
      return 'fiscal';
    case 'createMethod':
    case 'updateMethod':
      return 'meios';
  }
}

export function financePath(storeId: string, tab: FinanceTab): string {
  return `/finance?${new URLSearchParams({ storeId, tab })}`;
}

/** What the user is asked to continue after a step-up. */
export function commandLabel(command: FinanceCommand): string {
  switch (command.type) {
    case 'approveClosing':
      return `Aprovar o fecho ${command.label}`;
    case 'rejectClosing':
      return `Rejeitar o fecho ${command.label}`;
    case 'financialClose':
      return `Fechar financeiramente o dia ${command.label}`;
    case 'createSeries':
      return `Criar a série ${command.body.code}`;
    case 'deactivateSeries':
      return `Desativar a série ${command.label}`;
    case 'createMethod':
      return `Criar o meio de pagamento ${command.body.name}`;
    case 'updateMethod':
      return `Alterar o meio de pagamento ${command.body.name}`;
  }
}

/** Sends a command; a 409 IDEMPOTENCY_IN_PROGRESS waits and is resent under the same key (FE-F25). */
export function sendCommand(api: ApiClient, command: FinanceCommand): Promise<unknown> {
  return sendWaitingWhileInProgress(() => request(api, command)).then(({ data }) => data);
}

function request(api: ApiClient, command: FinanceCommand) {
  switch (command.type) {
    case 'approveClosing':
      return api.POST(`${B}/cash/closings/{id}/approve`, {
        params: { path: { id: command.closingId }, header: { 'Idempotency-Key': command.idempotencyKey } },
      });
    case 'rejectClosing':
      return api.POST(`${B}/cash/closings/{id}/reject`, {
        params: { path: { id: command.closingId }, header: { 'Idempotency-Key': command.idempotencyKey } },
        body: { reason: command.reason },
      });
    case 'financialClose':
      return api.POST(`${B}/cash/days/{id}/financial-close`, {
        params: { path: { id: command.dayId }, header: { 'Idempotency-Key': command.idempotencyKey } },
      });
    case 'createSeries':
      return api.POST(`${B}/fiscal/series`, { body: command.body });
    case 'deactivateSeries':
      return api.POST(`${B}/fiscal/series/{id}/deactivate`, { params: { path: { id: command.seriesId } } });
    case 'createMethod':
      return api.POST(`${B}/payment-methods`, { body: command.body });
    case 'updateMethod':
      return api.PUT(`${B}/payment-methods/{id}`, { params: { path: { id: command.methodId } }, body: command.body });
  }
}

type Stored = Pick<PendingCommand, 'method' | 'path' | 'body' | 'idempotencyKey'>;

function stored(command: FinanceCommand): Stored {
  const post = { method: 'POST' as const, idempotencyKey: null, body: null };
  switch (command.type) {
    case 'approveClosing':
      return { ...post, path: `${B}/cash/closings/${command.closingId}/approve`, idempotencyKey: command.idempotencyKey };
    case 'rejectClosing':
      return {
        ...post,
        path: `${B}/cash/closings/${command.closingId}/reject`,
        body: { reason: command.reason },
        idempotencyKey: command.idempotencyKey,
      };
    case 'financialClose':
      return { ...post, path: `${B}/cash/days/${command.dayId}/financial-close`, idempotencyKey: command.idempotencyKey };
    case 'createSeries':
      return { ...post, path: `${B}/fiscal/series`, body: command.body };
    case 'deactivateSeries':
      return { ...post, path: `${B}/fiscal/series/${command.seriesId}/deactivate` };
    case 'createMethod':
      return { ...post, path: `${B}/payment-methods`, body: command.body };
    case 'updateMethod':
      return { method: 'PUT', path: `${B}/payment-methods/${command.methodId}`, body: command.body, idempotencyKey: null };
  }
}

/** What a step-up keeps of a command (FC §6): the request, its key and what to name it, never a secret. */
export function toPendingCommand(command: FinanceCommand, storeId: string, tenantId: string): Omit<PendingCommand, 'savedAt'> {
  const label = 'label' in command ? command.label : '';
  return { ...stored(command), returnTo: financePath(storeId, commandTab(command)), label, tenantId };
}

const seriesBody = z.object({ storeId: z.uuid(), code: z.string().min(1).max(20), firstNumber: z.int().positive().optional() });
const methodCreateBody = z.object({
  code: z.string().min(1).max(30),
  kind: z.enum(['CASH', 'EXTERNAL_TERMINAL', 'INTEGRATED']),
  name: z.string().min(1).max(120),
  providerCode: z.string().max(60).optional(),
});
const methodUpdateBody = z.object({
  name: z.string().min(1).max(120),
  active: z.boolean(),
  providerCode: z.string().max(60).optional(),
  version: z.int().nonnegative(),
});
const reasonBody = z.object({ reason: z.string().min(1).max(500) });

type Parser = (match: RegExpExecArray, pending: PendingCommand) => FinanceCommand | undefined;

/** One entry per stored request: its method, its path and how to read it back. */
const PARSERS: [PendingCommand['method'], RegExp, Parser][] = [
  ['POST', new RegExp(`^${B}/cash/closings/${ID}/approve$`), (m, p) =>
    p.idempotencyKey ? { type: 'approveClosing', closingId: m[1]!, label: p.label, idempotencyKey: p.idempotencyKey } : undefined],
  ['POST', new RegExp(`^${B}/cash/closings/${ID}/reject$`), (m, p) => {
    const body = reasonBody.safeParse(p.body);
    return body.success && p.idempotencyKey
      ? { type: 'rejectClosing', closingId: m[1]!, label: p.label, reason: body.data.reason, idempotencyKey: p.idempotencyKey }
      : undefined;
  }],
  ['POST', new RegExp(`^${B}/cash/days/${ID}/financial-close$`), (m, p) =>
    p.idempotencyKey ? { type: 'financialClose', dayId: m[1]!, label: p.label, idempotencyKey: p.idempotencyKey } : undefined],
  ['POST', new RegExp(`^${B}/fiscal/series$`), (_m, p) => {
    const body = seriesBody.safeParse(p.body);
    return body.success ? { type: 'createSeries', body: body.data } : undefined;
  }],
  ['POST', new RegExp(`^${B}/fiscal/series/${ID}/deactivate$`), (m, p) => ({ type: 'deactivateSeries', seriesId: m[1]!, label: p.label })],
  ['POST', new RegExp(`^${B}/payment-methods$`), (_m, p) => {
    const body = methodCreateBody.safeParse(p.body);
    return body.success ? { type: 'createMethod', body: body.data } : undefined;
  }],
  ['PUT', new RegExp(`^${B}/payment-methods/${ID}$`), (m, p) => {
    const body = methodUpdateBody.safeParse(p.body);
    return body.success ? { type: 'updateMethod', methodId: m[1]!, body: body.data } : undefined;
  }],
];

/**
 * The finance command a stored request is, when it was kept for this store's finance screen; undefined for anything
 * else (another screen's command is left for that screen). A command whose contract takes a key must come with one.
 */
export function fromPendingCommand(pending: PendingCommand, storeId: string): FinanceCommand | undefined {
  for (const [method, path, parse] of PARSERS) {
    const match = path.exec(pending.path);
    if (pending.method !== method || !match) continue;
    const command = parse(match, pending);
    return command && pending.returnTo === financePath(storeId, commandTab(command)) ? command : undefined;
  }
  return undefined;
}

/** Whether the session holds `permission` company-wide or in this store; a convenience, the server decides. */
export function holds(permissions: readonly string[] | undefined, permission: string, storeId: string): boolean {
  return (permissions ?? []).some((held) => held === `PERM_${permission}` || held === `PERM_${permission}@${storeId}`);
}
