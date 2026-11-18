import { ApiError } from '@restaurantpro/api-client';
import { isUnavailable } from './errors';

/** Portuguese text for the error codes the BackOffice meets (FC §9); unknown codes show the server's title. */
const messages: Record<string, string> = {
  TENANT_FORBIDDEN: 'Sem acesso a esta empresa.',
  TENANT_SUSPENDED: 'Esta empresa está suspensa.',
  TENANT_CLOSED: 'Esta empresa está encerrada.',
  TENANT_SELECTION_INVALID: 'Empresa inválida.',
  // FE-F16: the company selected in the BFF session is not the one this page was loaded for (another tab switched).
  TENANT_CHANGED: 'A empresa ativa mudou noutro separador. Recarregue a página antes de continuar.',
  MFA_STEP_UP_REQUIRED: 'Esta ação pede que confirme a sua identidade.',
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
