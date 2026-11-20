import { Badge } from '@restaurantpro/ui';

/**
 * The fixed Stitch colours (DESIGN.md "Route chips") for the conventional production-destination codes; any other code
 * a tenant chose shows as itself, on a neutral badge.
 */
const KNOWN: Record<string, { label: string; className: string }> = {
  KITCHEN: { label: 'Cozinha', className: 'bg-route-kitchen text-background' },
  BAR: { label: 'Bar', className: 'bg-route-bar text-background' },
  COPA: { label: 'Copa', className: 'bg-route-copa text-background' },
  PANTRY: { label: 'Copa', className: 'bg-route-copa text-background' },
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
