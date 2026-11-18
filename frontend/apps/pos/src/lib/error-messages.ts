import { ApiError } from '@restaurantpro/api-client';

/** Portuguese text for the backend's known codes (FC §9); unknown codes show the answer's title. */
const MESSAGES: Record<string, string> = {
  ENROLMENT_CODE_INVALID:
    'Código inválido, já utilizado ou expirado. Confirme o código ou peça um novo ao administrador.',
  DEVICE_KEY_INVALID: 'O servidor recusou a chave deste terminal. Contacte o suporte.',
  PIN_UNCHANGED: 'O novo PIN tem de ser diferente do PIN definido pelo gerente.',
  PIN_CHANGE_NOT_REQUIRED: 'O PIN já foi alterado. Inicie sessão de novo.',
};

export const OFFLINE = 'Sem ligação — nenhuma operação é confirmada';

/**
 * @param offline the text for "no answer" (network, 502, 503), where the screen can say what did not happen
 */
export function isOffline(error: ApiError): boolean {
  return error.status === 0 || error.status === 502 || error.status === 503;
}

export function errorMessage(error: ApiError, { offline = `${OFFLINE}.` }: { offline?: string } = {}): string {
  if (isOffline(error)) return offline;
  if (error.status === 429) return `Demasiadas tentativas. Tente novamente dentro de ${wait(error)}.`;
  const known = MESSAGES[error.code];
  if (known) return known;
  if (error.status >= 500) return `Erro inesperado (${error.code}).`;
  return error.title;
}

function wait(error: ApiError): string {
  if (error.retryAfter !== undefined) return `${error.retryAfter} s`;
  // The enrolment throttle's window is one minute (backend EnrolmentThrottle), and it sends no Retry-After.
  return error.code === 'ENROLMENT_THROTTLED' ? 'um minuto' : 'alguns instantes';
}
