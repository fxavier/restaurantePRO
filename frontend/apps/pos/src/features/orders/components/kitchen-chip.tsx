import { StatusPill } from '@restaurantpro/ui';
import { CircleCheck, CookingPot, HandPlatter } from 'lucide-react';
import type { KitchenState } from '../hooks/use-round-kitchen';

/**
 * A sent round's kitchen state, beside its heading (the table map's order and the order screen's panel): "servida"
 * (every station has bumped it; the info tint, so it does not read as the green "pronta"), "pronta" (every station has
 * it ready, not all bumped) or "em preparação" (one has started); a queued round has no chip. The shared tinted pill,
 * as tall as the heading's line.
 */
export function KitchenChip({ state }: { state: KitchenState | undefined }) {
  if (state === 'SERVED') {
    return (
      <StatusPill tone="info" icon={HandPlatter} className="px-2 py-0 leading-4">
        servida
      </StatusPill>
    );
  }
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
