import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, type FeatureMessages, isOffline } from '../../lib/error-messages';

/** The production codes' Portuguese texts (FC §9), passed to `errorMessage`. */
const KITCHEN_MESSAGES: FeatureMessages = {
  ILLEGAL_KITCHEN_STATE: 'A ronda já mudou de estado noutro ecrã.',
  NOT_KITCHEN_WORK: 'Este talão não é uma ronda; não se prepara no ecrã de cozinha.',
};

/** A command refused because the round moved meanwhile (409 `ILLEGAL_KITCHEN_STATE`, or a stale version). */
export const CHANGED_ELSEWHERE = 'A ronda foi alterada noutro ecrã. O ecrã foi atualizado; reveja e tente de novo.';

const LOAD_FAILED = 'Não foi possível carregar as rondas.';
const INCOMPLETE = 'A resposta do servidor está incompleta.';

/** Why a read of the display failed, in Portuguese. */
export const loadFailure = (error: Error) =>
  say(error, { failed: LOAD_FAILED, offline: 'Sem ligação — não foi possível carregar as rondas.' });

/** Why a command on a round failed, in Portuguese; no answer means it was not confirmed. */
export const refusal = (error: Error) =>
  say(error, { failed: 'Não foi possível confirmar a ronda.', offline: 'Sem ligação — a ronda não foi atualizada.', bare: true });

/**
 * @param failed what did not happen, said first (unless `bare`) and before an incomplete answer
 * @param offline the whole text when there was no answer
 * @param bare the backend's reason alone, without `failed` before it
 */
function say(error: Error, { failed, offline, bare = false }: { failed: string; offline: string; bare?: boolean }): string {
  if (!(error instanceof ApiError)) return `${failed} ${INCOMPLETE}`;
  const reason = errorMessage(error, { offline, messages: KITCHEN_MESSAGES });
  return bare || isOffline(error) ? reason : `${failed} ${reason}`;
}
