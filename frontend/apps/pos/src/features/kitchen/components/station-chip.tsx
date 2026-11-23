import { Badge } from '@restaurantpro/ui';

/**
 * A station's route chip (DESIGN.md "Route chips"): the fixed Stitch colours for the conventional destination codes,
 * any other code as itself on a neutral badge. A minimal copy of the catalog's DestinationBadge, which is that
 * feature's internal (FC: a feature never imports another's internals); move both to one shared place when a third
 * feature needs it.
 */
// Light theme: the route colour as border and tint, the text in `foreground` (white on these hues is below AA).
const KNOWN: Record<string, { label: string; className: string }> = {
  KITCHEN: { label: 'Cozinha', className: 'border-route-kitchen bg-route-kitchen/15 text-foreground' },
  BAR: { label: 'Bar', className: 'border-route-bar bg-route-bar/15 text-foreground' },
  COPA: { label: 'Copa', className: 'border-route-copa bg-route-copa/15 text-foreground' },
  PANTRY: { label: 'Copa', className: 'border-route-copa bg-route-copa/15 text-foreground' },
};

export function StationChip({ code }: { code: string }) {
  const known = KNOWN[code];
  return (
    <Badge variant="secondary" className={known?.className}>
      {known?.label ?? code}
    </Badge>
  );
}
