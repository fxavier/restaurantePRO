import { ApiError } from '@restaurantpro/api-client';
import { isUnavailable } from './errors';

/**
 * Portuguese text for the error codes the BackOffice meets (FC §9). Every 409 is coded (FE-F29); an unknown 409 code
 * still gets a Portuguese generic, never the server's English title (FE-F32). Other unknown codes show the title.
 */
const messages: Record<string, string> = {
  TENANT_FORBIDDEN: 'Sem acesso a esta empresa.',
  TENANT_SUSPENDED: 'Esta empresa está suspensa.',
  TENANT_CLOSED: 'Esta empresa está encerrada.',
  TENANT_SELECTION_INVALID: 'Empresa inválida.',
  MFA_STEP_UP_REQUIRED: 'Esta ação pede que confirme a sua identidade.',
  VERSION_CONFLICT: 'Foi alterado por outra pessoa. Recarregue e tente de novo.',
  IDEMPOTENCY_IN_PROGRESS: 'O pedido anterior ainda está a ser processado. Tente novamente dentro de instantes.',
  // taken codes and names of the BackOffice configuration screens (FE-F25, FE-F29)
  TERMINAL_CODE_TAKEN: 'Já existe um terminal com este código nesta loja.',
  STORE_CODE_TAKEN: 'Já existe uma loja com este código.',
  REGISTER_CODE_TAKEN: 'Já existe uma caixa com este código nesta loja.',
  CATALOG_CODE_TAKEN: 'Este código já está a ser utilizado no catálogo.',
  DINING_CODE_TAKEN: 'Já existe uma área ou mesa com este código nesta loja.',
  EMPLOYEE_CODE_TAKEN: 'Já existe um empregado com este código.',
  EMPLOYEE_ACCOUNT_TAKEN: 'Este empregado já tem uma conta.',
  USERNAME_TAKEN: 'Este nome de utilizador já está a ser utilizado.',
  ROLE_NAME_TAKEN: 'Já existe uma função com este nome.',
  ROLE_ALREADY_ASSIGNED: 'O empregado já tem esta função nesta loja.',
  SERIES_CODE_TAKEN: 'A loja já tem uma série com este código.',
  ACTIVE_SERIES_EXISTS: 'A loja já tem uma série de venda ativa. Desative-a primeiro.',
  // state conflicts (FE-F29)
  ILLEGAL_TERMINAL_STATE: 'O terminal está revogado e não aceita esta ação.',
  ILLEGAL_CASH_STATE: 'A caixa não permite esta operação no estado atual. Atualize e tente de novo.',
  ILLEGAL_RELEASE_STATE: 'A publicação não permite esta operação no estado atual. Atualize e tente de novo.',
  ILLEGAL_DOCUMENT_STATE: 'O documento não permite esta operação no estado atual.',
  ILLEGAL_SERIES_STATE: 'A série não permite esta operação no estado atual. Atualize e tente de novo.',
  REPRINT_IN_PROGRESS: 'Outra reimpressão está em curso. Tente novamente.',
};

const UNKNOWN_CONFLICT = 'Os dados foram alterados entretanto. Atualize e tente de novo.';

/** A feature's own Portuguese text per backend code; it wins over the shared list, so a feature never edits this file. */
export type FeatureMessages = Readonly<Record<string, string>>;

/** @param options.messages the calling feature's texts for its codes; "no connection" still comes first */
export function errorMessage(error: unknown, options: { messages?: FeatureMessages } = {}): string {
  if (isUnavailable(error)) return 'Sem ligação — a operação não foi confirmada.';
  if (!(error instanceof ApiError)) return 'Erro inesperado.';
  const known = options.messages?.[error.code] ?? messages[error.code];
  if (known) return known;
  if (error.status === 403) return 'Sem permissão para esta ação.';
  if (error.status === 409) return UNKNOWN_CONFLICT;
  if (error.status >= 500) return `Erro inesperado (${error.status}).`;
  return error.title;
}
