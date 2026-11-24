import { StatusBadge, type StatusMap } from '@restaurantpro/ui';

/**
 * Moved to `@restaurantpro/ui` (`StatusPill`, `StatusBadge look="tint"`, `TONE_TINT`, `TONE_CARD`, `TONE_INK`), the one
 * source of a state's tint. No POS screen imports this module any more; it stays only until it is removed.
 */
export { StatusPill as StateChip, TONE_CARD, TONE_TINT as TONE_TEXT } from '@restaurantpro/ui';

/** `StatusBadge look="tint"`. */
export function StatusChip<S extends string>({ status, statuses }: { status: S; statuses: StatusMap<S> }) {
  return <StatusBadge look="tint" status={status} statuses={statuses} />;
}
