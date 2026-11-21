import type { FeatureMessages } from '../../lib/error-messages';

/** Portuguese text for the codes the finance commands meet; the shared list covers the rest. */
export const FINANCE_MESSAGES: FeatureMessages = {
  CLOSINGS_NOT_APPROVED: 'Há caixas do dia sem o fecho aprovado. Aprove-os antes do fecho financeiro.',
  OPEN_SESSIONS: 'O dia ainda tem sessões de caixa abertas.',
  CLOSING_STALE: 'O fecho já não corresponde ao que a caixa tem agora. A caixa tem de ser fechada de novo.',
  PAYMENT_METHOD_CODE_TAKEN: 'Já existe um meio de pagamento com este código.',
};
