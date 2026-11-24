import { CircleCheck, CookingPot } from 'lucide-react';
import type { KitchenState } from '../hooks/use-round-kitchen';

/**
 * A sent round's kitchen state, beside its heading (the table map's order and the order screen's panel): "pronta"
 * (every station has it ready or served) or "em preparação" (one has started); a queued round has no chip.
 */
export function KitchenChip({ state }: { state: KitchenState | undefined }) {
  if (state === 'READY') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-success-tint px-2 text-sm leading-4 font-medium text-success-text">
        <CircleCheck aria-hidden="true" className="size-4" />
        pronta
      </span>
    );
  }
  if (state === 'IN_PREPARATION') {
    return (
      <span className="inline-flex items-center gap-1 rounded-full bg-warning-tint px-2 text-sm leading-4 font-medium text-warning-text">
        <CookingPot aria-hidden="true" className="size-4" />
        em preparação
      </span>
    );
  }
  return null;
}
