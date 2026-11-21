import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, type FeatureMessages } from '../../lib/error-messages';
import { isInProgress, isUnknownOutcome } from '../../lib/use-intent';

/** The reservations' own codes (D126, P47), in Portuguese; they win over the shared list. */
const MESSAGES: FeatureMessages = {
  RESERVATION_NOT_BOOKED: 'A reserva já não está por chegar: foi sentada, cancelada ou dada como não comparência.',
  RESERVATION_IN_THE_PAST: 'Essa hora já passou. Escolha uma hora futura.',
  RESERVATION_NOT_STARTED: 'A reserva ainda não começou: só pode dar a não comparência depois da hora marcada.',
  RESERVATION_OTHER_STORE: 'Esta reserva não pertence à loja deste terminal.',
  TABLE_OTHER_STORE: 'Uma das mesas escolhidas não pertence a esta loja.',
  CUSTOMER_NOT_FOUND: 'O cliente associado já não existe. Escolha outro cliente.',
  TABLE_LOCKED: 'A mesa está bloqueada. Um gerente tem de a desbloquear, ou escolha outra.',
  TABLE_OCCUPIED: 'A mesa acabou de ser aberta noutro terminal. Escolha outra.',
  VERSION_CONFLICT: 'A reserva foi alterada noutro terminal. A lista foi atualizada: reveja e tente de novo.',
  // The backend refuses an unusable phone with an uncoded 422, the only uncoded rule of a reservation.
  HTTP_422: 'O telefone tem de ter entre 4 e 20 algarismos.',
};

/**
 * What to say when a reservation command failed.
 *
 * @param unknown the text for an outcome the terminal cannot know (no answer): "Tentar novamente" follows it
 */
export function refusal(error: Error, unknown: string): string {
  if (!(error instanceof ApiError)) return 'A resposta do servidor está incompleta. Atualize e confirme o resultado.';
  if (isInProgress(error)) return 'O servidor ainda está a tratar este pedido. Tente novamente dentro de instantes.';
  if (isUnknownOutcome(error)) return unknown;
  return errorMessage(error, { messages: MESSAGES });
}
