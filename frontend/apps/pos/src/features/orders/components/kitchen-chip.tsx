import { StatusPill } from '@restaurantpro/ui';
import { CircleCheck, CookingPot } from 'lucide-react';
import type { KitchenState } from '../hooks/use-round-kitchen';

/**
 * A sent round's kitchen state, beside its heading (the table map's order and the order screen's panel): "pronta"
 * (every station has it ready or served) or "em preparação" (one has started); a queued round has no chip. The shared
 * tinted pill, as tall as the heading's line.
 */
export function KitchenChip({ state }: { state: KitchenState | undefined }) {
  if (state === 'READY') {
    return (
      <StatusPill tone="success" icon={CircleCheck} className="px-2 py-0 leading-4">
        pronta
      </StatusPill>
    );
  }
  if (state === 'IN_PREPARATION') {
    return (
      <StatusPill tone="warning" icon={CookingPot} className="px-2 py-0 leading-4">
        em preparação
      </StatusPill>
    );
  }
  return null;
}
