import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, type FeatureMessages } from '../../lib/error-messages';
import { isInProgress } from '../../lib/use-intent';

/** The cash and settlement codes the POS cash screen meets (CashRule, SettlementRule), in Portuguese. */
const MESSAGES: FeatureMessages = {
  DAY_NOT_OPEN: 'O dia de caixa não está aberto.',
  DAY_CLOSING_ONLY: 'Este dia só aceita fechos e correções: não abre sessões novas.',
  DAY_FINANCIALLY_CLOSED: 'O dia já está fechado financeiramente.',
  REGISTER_CLOSED_FOR_DAY: 'Esta caixa já está em fecho neste dia.',
  REGISTER_INACTIVE: 'Esta caixa está inativa.',
  SESSION_NOT_OPEN: 'A sessão de caixa já não está aberta.',
  SESSION_STILL_OPEN: 'Esta caixa ainda tem uma sessão aberta. Feche a sessão antes de preparar o fecho.',
  NO_SESSIONS: 'Esta caixa não teve sessões neste dia: não há nada a fechar.',
  OPEN_SESSIONS: 'Ainda há sessões de caixa abertas neste dia.',
  OPEN_ORDERS: 'Ainda há pedidos abertos neste dia.',
  FISCAL_PENDING: 'Ainda há documentos fiscais pendentes neste dia.',
  PAYMENT_IN_DOUBT: 'Há pagamentos pendentes de reconciliação. Resolva-os antes de fechar.',
  JUSTIFICATION_REQUIRED: 'Justifique cada diferença antes de submeter o fecho.',
  INVALID_AMOUNT: 'Valor inválido: tem de ser positivo e ter as casas decimais da moeda.',
  CURRENCY_MISMATCH: 'O valor não está na moeda da loja.',
  SELF_APPROVAL: 'O aprovador tem de ser outro empregado, não o operador da caixa.',
  CLOSING_STALE: 'O fecho mudou entretanto. Prepare-o de novo.',
};

/**
 * What a cash command's failure says: `what` names the command ("a abertura da sessão") for the outcomes where the
 * screen must say it was not confirmed.
 */
export function cashRefusal(error: Error, what: string): string {
  if (!(error instanceof ApiError)) return 'Não foi possível concluir: a resposta do servidor está incompleta.';
  if (isInProgress(error)) return `O servidor ainda está a processar ${what}. Tente novamente dentro de instantes.`;
  return errorMessage(error, { offline: `Sem ligação — não foi possível confirmar ${what}.`, messages: MESSAGES });
}
