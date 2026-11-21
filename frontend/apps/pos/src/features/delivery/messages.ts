import { ApiError } from '@restaurantpro/api-client';
import { errorMessage, type FeatureMessages, isOffline } from '../../lib/error-messages';

/** The delivery and customer codes' Portuguese texts (FC §9), passed to `errorMessage`. */
export const DELIVERY_MESSAGES: FeatureMessages = {
  DELIVERY_HAS_DRAFTS: 'O pedido tem artigos por enviar. Envie-os ou apague-os antes de expedir.',
  DELIVERY_NOTHING_CONFIRMED: 'O pedido ainda não tem artigos enviados. Envie pelo menos uma ronda antes de expedir.',
  COURIER_NOT_IN_STORE: 'O estafeta não é um empregado ativo desta loja.',
  CUSTOMER_REQUIRED: 'Uma entrega precisa de cliente, morada e zona.',
  CUSTOMER_INACTIVE: 'O cliente está inativo.',
  ZONE_INACTIVE: 'A zona de entrega está inativa. Escolha outra.',
  ZONE_OTHER_STORE: 'A zona de entrega não pertence a esta loja.',
  FEE_ITEM_NOT_PUBLISHED: 'A taxa desta zona não está no catálogo publicado. Peça a um gestor que a publique.',
  PHONE_EXISTS: 'Este telefone já está registado.',
};

/** A transition refused because the delivery moved meanwhile (409 `DELIVERY_STATE`, or a stale version). */
export const CHANGED_ELSEWHERE = 'A entrega foi alterada noutro terminal. O quadro foi atualizado; reveja e tente de novo.';

/** A "Criar entrega" or "Abrir takeaway" without an answer: closing would make the next tap a second order. */
export const ORDER_UNSETTLED = 'O pedido pode já ter sido aberto. Toque em "Tentar novamente" para o confirmar sem abrir outro.';

/** A "Criar cliente" without an answer: going back would make the next tap a second customer. */
export const CUSTOMER_UNSETTLED = 'O cliente pode já ter sido criado. Toque em "Tentar novamente" para o confirmar sem criar outro.';

/**
 * Why a command failed, in Portuguese.
 *
 * @param what the command as a feminine noun phrase ("a expedição"): no answer means it was not confirmed
 */
export function refusal(error: Error, what: string): string {
  if (!(error instanceof ApiError)) return `Não foi possível confirmar ${what}. A resposta do servidor está incompleta.`;
  if (isOffline(error)) return `Sem ligação — ${what} não foi confirmada.`;
  return errorMessage(error, { messages: DELIVERY_MESSAGES });
}

/**
 * Why a read failed, in Portuguese.
 *
 * @param what what was read, with its article ("as entregas")
 */
export function loadFailure(error: Error, what: string): string {
  const failed = `Não foi possível carregar ${what}.`;
  if (!(error instanceof ApiError)) return `${failed} A resposta do servidor está incompleta.`;
  if (isOffline(error)) return `Sem ligação — não foi possível carregar ${what}.`;
  return `${failed} ${errorMessage(error, { messages: DELIVERY_MESSAGES })}`;
}
