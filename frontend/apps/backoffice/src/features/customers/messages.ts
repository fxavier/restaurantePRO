import type { FeatureMessages } from '../../lib/error-messages';

/** pt-PT text for the customers and loyalty codes (backend CustomerRule, CustomersConflictException, LoyaltyRule). */
export const customerMessages: FeatureMessages = {
  CUSTOMER_INACTIVE: 'O cliente está inativo.',
  CREDIT_ACCOUNT_MISSING: 'O cliente ainda não tem conta de crédito. Defina primeiro um limite.',
  CREDIT_ACCOUNT_EXISTS: 'A conta de crédito foi aberta ao mesmo tempo noutro posto. Tente novamente.',
  CREDIT_BLOCKED: 'O crédito deste cliente está bloqueado.',
  INVALID_AMOUNT: 'Valor inválido.',
  INVALID_PERIOD: 'O período indicado é inválido.',
  PHONE_EXISTS: 'O cliente já tem este telefone.',
  LOYALTY_NOT_MEMBER: 'O cliente não aderiu à fidelização.',
  LOYALTY_ACCOUNT_SUSPENDED: 'A conta de fidelização está suspensa.',
  LOYALTY_ACCOUNT_STATE: 'A conta de fidelização não permite esta operação no estado atual. Atualize e tente de novo.',
  LOYALTY_INSUFFICIENT_POINTS: 'O cliente não tem pontos suficientes para este débito.',
  REASON_REQUIRED: 'Indique o motivo.',
};
