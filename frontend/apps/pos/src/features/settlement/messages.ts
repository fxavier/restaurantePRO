import type { FeatureMessages } from '../../lib/error-messages';

/** Portuguese text for settlement's codes (`SettlementRule`, FC §9), passed to `errorMessage`. */
export const SETTLEMENT_MESSAGES: FeatureMessages = {
  NO_OPEN_CASH_SESSION: 'Não tem uma sessão de caixa aberta nesta loja. Abra a caixa antes de cobrar.',
  AMOUNT_EXCEEDS_BALANCE: 'O valor é superior ao que falta pagar.',
  UNKNOWN_PAYMENT_METHOD: 'Este método de pagamento não está ativo.',
  INVALID_AMOUNT: 'O valor tem de ser positivo e na moeda da conta.',
  TENDERED_BELOW_AMOUNT: 'O valor recebido é inferior ao valor a pagar.',
  CHANGE_FOR_CASH_ONLY: 'Só há troco em numerário.',
  NOT_SETTLED: 'A conta ainda não está paga na totalidade.',
  NOTHING_TO_BILL: 'O pedido não tem nada a faturar.',
  FISCAL_PENDING: 'O documento ficou pendente de emissão. Tente novamente.',
  PAYMENT_IN_DOUBT: 'Há um pagamento por confirmar nesta conta.',
  QUANTITY_ALREADY_PARTITIONED: 'Parte desta quantidade já está noutra divisão.',
  QUANTITY_EXCEEDS_NET: 'A quantidade é superior à que está por faturar.',
  AMOUNT_EXCEEDS_RESIDUAL: 'O valor é superior ao que falta dividir.',
  NOTHING_TO_SPLIT: 'Não há valor suficiente para dividir em tantas partes.',
  PARTITION_IN_USE: 'Esta divisão já tem pagamentos ou documento e não pode ser cancelada.',
  PARTITION_NOT_INVOICEABLE: 'Só uma divisão por artigos tem documento próprio.',
  SPLIT_ROUNDING_LIMIT: 'Esta divisão não é possível por arredondamento. Divida de outra forma.',
  ILLEGAL_ATTEMPT_STATE: 'O pagamento já não permite esta operação. A conta foi atualizada.',
  ILLEGAL_PARTITION_STATE: 'A divisão já não permite esta operação. A conta foi atualizada.',
  DELIVERY_NOT_DELIVERED: 'A entrega ainda não foi concluída.',
};
