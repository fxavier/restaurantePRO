import { Badge } from '@restaurantpro/ui';

/**
 * The fixed Stitch colours (DESIGN.md "Route chips") for the conventional production-destination codes; any other code
 * a tenant chose shows as itself, on a neutral badge.
 */
// Light theme: the route colour as border and tint, the text in `foreground` (white on these hues is below AA).
const KNOWN: Record<string, { label: string; className: string }> = {
  KITCHEN: { label: 'Cozinha', className: 'border-route-kitchen bg-route-kitchen/15 text-foreground' },
  BAR: { label: 'Bar', className: 'border-route-bar bg-route-bar/15 text-foreground' },
  COPA: { label: 'Copa', className: 'border-route-copa bg-route-copa/15 text-foreground' },
  PANTRY: { label: 'Copa', className: 'border-route-copa bg-route-copa/15 text-foreground' },
};

/** Where an item is prepared (the catalog's `productionDestination`); nothing when it is not prepared. */
export function DestinationBadge({ code }: { code: string | null | undefined }) {
  if (!code) return null;
  const known = KNOWN[code];
  return (
    <Badge variant="secondary" className={known?.className}>
      {known?.label ?? code}
    </Badge>
  );
}
