import { Badge } from '@restaurantpro/ui';

/**
 * A station's route chip (DESIGN.md "Route chips"): the fixed Stitch colours for the conventional destination codes,
 * any other code as itself on a neutral badge. A minimal copy of the catalog's DestinationBadge, which is that
 * feature's internal (FC: a feature never imports another's internals); move both to one shared place when a third
 * feature needs it.
 */
// Light theme: the route colour as border and tint, the text in `foreground` (white on these hues is below AA); the
// dot is the route colour alone, for the filter pills (decorative: the name says it).
const KITCHEN = { label: 'Cozinha', chip: 'border-route-kitchen bg-route-kitchen/15 text-foreground', dot: 'bg-route-kitchen' };
const BAR = { label: 'Bar', chip: 'border-route-bar bg-route-bar/15 text-foreground', dot: 'bg-route-bar' };
const COPA = { label: 'Copa', chip: 'border-route-copa bg-route-copa/15 text-foreground', dot: 'bg-route-copa' };
const KNOWN: Record<string, { label: string; chip: string; dot: string }> = { KITCHEN, BAR, COPA, PANTRY: COPA };

export function StationChip({ code }: { code: string }) {
  const known = KNOWN[code];
  return (
    <Badge variant="secondary" className={known?.chip}>
      {known?.label ?? code}
    </Badge>
  );
}

/**
 * A station named on a filter pill: its route colour as a dot, then its name in the pill's own text colour, so it
 * reads on the pressed (green) pill as on the others.
 */
export function StationName({ code }: { code: string }) {
  const known = KNOWN[code];
  return (
    <>
      <span aria-hidden="true" className={`size-3 shrink-0 rounded-full border border-card ${known?.dot ?? 'bg-muted-foreground'}`} />
      {known?.label ?? code}
    </>
  );
}
