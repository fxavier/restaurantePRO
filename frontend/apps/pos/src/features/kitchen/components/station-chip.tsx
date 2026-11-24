import { Badge, routeChipFill, routeDot } from '@restaurantpro/ui';

/**
 * A station's route chip (DESIGN.md "Route chips"): the shared route hue (`routeChipFill`, COZINHA the kitchen's) as a
 * border and tint with `foreground` text, the design's stations by name and any other code as itself. The names are
 * a minimal copy of the catalog's DestinationBadge, which is that feature's internal (FC: a feature never imports
 * another's internals).
 */
const LABELS: Record<string, string> = { KITCHEN: 'Cozinha', BAR: 'Bar', COPA: 'Copa', PANTRY: 'Copa' };

export function StationChip({ code }: { code: string }) {
  return (
    <Badge variant="secondary" className={routeChipFill(code)}>
      {LABELS[code] ?? code}
    </Badge>
  );
}

/**
 * A station named on a filter pill: its route colour as a dot, then its name in the pill's own text colour, so it
 * reads on the pressed (green) pill as on the others.
 */
export function StationName({ code }: { code: string }) {
  return (
    <>
      <span aria-hidden="true" className={`size-3 shrink-0 rounded-full border border-card ${routeDot(code)}`} />
      {LABELS[code] ?? code}
    </>
  );
}
