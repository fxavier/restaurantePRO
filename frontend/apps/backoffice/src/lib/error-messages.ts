import { ApiError } from '@restaurantpro/api-client';
import { isUnavailable } from './errors';

/** Portuguese text for the error codes the BackOffice meets (FC §9); unknown codes show the server's title. */
const messages: Record<string, string> = {
  TENANT_FORBIDDEN: 'Sem acesso a esta empresa.',
  TENANT_SUSPENDED: 'Esta empresa está suspensa.',
  TENANT_CLOSED: 'Esta empresa está encerrada.',
  TENANT_SELECTION_INVALID: 'Empresa inválida.',
  MFA_STEP_UP_REQUIRED: 'Esta ação pede que confirme a sua identidade.',
  VERSION_CONFLICT: 'Foi alterado por outra pessoa. Recarregue e tente de novo.',
  IDEMPOTENCY_IN_PROGRESS: 'O pedido anterior ainda está a ser processado. Tente novamente dentro de instantes.',
};

export function errorMessage(error: unknown): string {
  if (isUnavailable(error)) return 'Sem ligação — a operação não foi confirmada.';
  if (!(error instanceof ApiError)) return 'Erro inesperado.';
  const known = messages[error.code];
  if (known) return known;
  if (error.status === 403) return 'Sem permissão para esta ação.';
  if (error.status >= 500) return `Erro inesperado (${error.status}).`;
  return error.title;
}
