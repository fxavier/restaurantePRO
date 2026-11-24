import { Badge, routeChipFill } from '@restaurantpro/ui';

/**
 * The production destination's chip (DESIGN.md "Route chips"): the shared route hue (`routeChipFill`, COZINHA the
 * kitchen's) as a border and tint with `foreground` text; the design's destinations by name, any other code a tenant
 * chose as itself.
 */
const LABELS: Record<string, string> = { KITCHEN: 'Cozinha', BAR: 'Bar', COPA: 'Copa', PANTRY: 'Copa' };

/** Where an item is prepared (the catalog's `productionDestination`); nothing when it is not prepared. */
export function DestinationBadge({ code }: { code: string | null | undefined }) {
  if (!code) return null;
  return (
    <Badge variant="secondary" className={routeChipFill(code)}>
      {LABELS[code] ?? code}
    </Badge>
  );
}
