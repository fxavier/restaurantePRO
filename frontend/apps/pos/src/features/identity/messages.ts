import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, isOffline } from '../../lib/error-messages';

/**
 * One answer for every refused login (D26): the backend does not say whether the code, the PIN or a lock was the
 * reason, and neither does this; it says that repeated failures lock, which only a manager undoes.
 */
export const LOGIN_REFUSED =
  'Código ou PIN inválido. Várias tentativas erradas bloqueiam o acesso: peça a um gerente para o desbloquear.';

export function loginError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Erro inesperado. Tente novamente.';
  // 400 is a code or PIN the backend's validation refused (blank, too long): the same generic answer.
  if (error.status === 401 || error.status === 400) return LOGIN_REFUSED;
  return errorMessage(error, { offline: 'Sem ligação — a sessão não foi iniciada. Tente novamente.' });
}

export function pinChangeError(error: Error): string {
  if (!(error instanceof ApiError)) return 'Erro inesperado. Tente novamente.';
  if (error.status === 401) return 'Não foi possível alterar o PIN. Peça ajuda a um gerente.';
  return errorMessage(error, { offline: 'Sem ligação — o PIN não foi alterado. Tente novamente.' });
}

/** A refusal (an answer) empties the PIN; "Sem ligação" keeps it, for "Tentar novamente". */
export const answered = (error: Error) => !(error instanceof ApiError && isOffline(error));
