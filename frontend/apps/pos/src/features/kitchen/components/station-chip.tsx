import { Badge } from '@restaurantpro/ui';

/**
 * A station's route chip (DESIGN.md "Route chips"): the fixed Stitch colours for the conventional destination codes,
 * any other code as itself on a neutral badge. A minimal copy of the catalog's DestinationBadge, which is that
 * feature's internal (FC: a feature never imports another's internals); move both to one shared place when a third
 * feature needs it.
 */
const KNOWN: Record<string, { label: string; className: string }> = {
  KITCHEN: { label: 'Cozinha', className: 'bg-route-kitchen text-background' },
  BAR: { label: 'Bar', className: 'bg-route-bar text-background' },
  COPA: { label: 'Copa', className: 'bg-route-copa text-background' },
  PANTRY: { label: 'Copa', className: 'bg-route-copa text-background' },
};

export function StationChip({ code }: { code: string }) {
  const known = KNOWN[code];
  return (
    <Badge variant="secondary" className={known?.className}>
      {known?.label ?? code}
    </Badge>
  );
}
