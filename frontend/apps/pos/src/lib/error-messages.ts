import { type ApiError, isUnavailable } from '@restaurantpro/api-client';

/** Portuguese text for the backend's known codes (FC §9); unknown codes show the answer's title. */
const MESSAGES: Record<string, string> = {
  ENROLMENT_CODE_INVALID:
    'Código inválido, já utilizado ou expirado. Confirme o código ou peça um novo ao administrador.',
  DEVICE_KEY_INVALID: 'O servidor recusou a chave deste terminal. Contacte o suporte.',
  PIN_UNCHANGED: 'O novo PIN tem de ser diferente do PIN definido pelo gerente.',
  PIN_CHANGE_NOT_REQUIRED: 'O PIN já foi alterado. Inicie sessão de novo.',
  TABLE_LOCKED: 'A mesa está bloqueada.',
  TABLE_OCCUPIED: 'A mesa acabou de ser aberta noutro terminal.',
  TABLE_INACTIVE: 'A mesa ou a sua área está inativa.',
  STORE_MISMATCH: 'Esta mesa não pertence à loja deste terminal.',
  ORDER_NOT_OPEN: 'Este pedido já não está aberto.',
  ORDER_FROZEN: 'O pedido está bloqueado para faturação e não aceita alterações.',
  ORDER_IN_SETTLEMENT: 'O pedido tem um pagamento em curso.',
  ITEM_NOT_IN_CATALOG: 'O artigo já não está no catálogo publicado.',
  ITEM_SOLD_OUT: 'O artigo está esgotado.',
  MENU_SELECTION_INVALID: 'As escolhas do menu não são válidas; reveja-as.',
  LINE_NOT_DRAFT: 'A linha já foi enviada e não pode ser alterada aqui.',
  FEE_LINE_LOCKED: 'A taxa de entrega não pode ser alterada.',
};

export const OFFLINE = 'Sem ligação — nenhuma operação é confirmada';

/**
 * @param offline the text for "no answer" (network, 502, 503, 504), where the screen can say what did not happen
 */
export function isOffline(error: ApiError): boolean {
  return isUnavailable(error);
}

export function errorMessage(error: ApiError, { offline = `${OFFLINE}.` }: { offline?: string } = {}): string {
  if (isOffline(error)) return offline;
  if (error.status === 429) return `Demasiadas tentativas. Tente novamente dentro de ${wait(error)}.`;
  const known = MESSAGES[error.code];
  if (known) return known;
  if (error.status === 403) return 'Sem permissão para esta ação.';
  if (error.status >= 500) return `Erro inesperado (${error.code}).`;
  return error.title;
}

function wait(error: ApiError): string {
  if (error.retryAfter !== undefined) return `${error.retryAfter} s`;
  // The enrolment throttle's window is one minute (backend EnrolmentThrottle), and it sends no Retry-After.
  return error.code === 'ENROLMENT_THROTTLED' ? 'um minuto' : 'alguns instantes';
}
