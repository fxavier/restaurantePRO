import { useQueryClient } from '@tanstack/react-query';
import { useServices } from '../../../lib/services';
import { useIntent } from '../../../lib/use-intent';
import { isChangedElsewhere, type KitchenCommand } from '../kitchen';
import { kitchenKey } from './use-kitchen-tickets';

const PATHS = {
  start: '/api/v1/pos/production-tickets/{id}/start',
  ready: '/api/v1/pos/production-tickets/{id}/ready',
  bump: '/api/v1/pos/production-tickets/{id}/bump',
  recall: '/api/v1/pos/production-tickets/{id}/recall',
} as const satisfies Record<KitchenCommand, string>;

type Move = { command: KitchenCommand; version: number };

/**
 * `POST /api/v1/pos/production-tickets/{id}/start|ready|bump|recall` (ADR 0044): one intent per tap, with its own key
 * (FC §8). The display is read again after any answer: the server's state is the one shown, never this command's guess.
 */
export function useKitchenCommand(ticketId: string, { onChangedElsewhere }: { onChangedElsewhere?: () => void } = {}) {
  const { api } = useServices();
  const queryClient = useQueryClient();
  const refresh = () => void queryClient.invalidateQueries({ queryKey: kitchenKey });
  return useIntent<Move, unknown>(
    async ({ command, version }, key) =>
      (
        await api.POST(PATHS[command], {
          params: { path: { id: ticketId }, header: { 'Idempotency-Key': key } },
          body: { version },
        })
      ).data,
    {
      onSuccess: refresh,
      onError(error) {
        refresh();
        if (isChangedElsewhere(error)) onChangedElsewhere?.();
      },
      onDiscard: refresh,
    },
  );
}
