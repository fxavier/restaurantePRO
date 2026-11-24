import { Badge, routeChipFill, routeDot, routeLabel } from '@restaurantpro/ui';

/**
 * A station's route chip (DESIGN.md "Route chips"): the shared route hue and name (`routeChipFill`, `routeLabel`:
 * COZINHA the kitchen's) as a border and tint with `foreground` text; any other code as itself.
 */
export function StationChip({ code }: { code: string }) {
  return (
    <Badge variant="secondary" className={routeChipFill(code)}>
      {routeLabel(code)}
    </Badge>
  );
}

/**
 * A station named on a filter pill: its route colour as a dot, then its name in the pill's own text colour, so it
 * reads on the pressed (green) pill as on the others.
 */
export function StationName({ code, withCode = false }: { code: string; withCode?: boolean }) {
  return (
    <>
      <span aria-hidden="true" className={`size-3 shrink-0 rounded-full border border-card ${routeDot(code)}`} />
      {routeLabel(code)}
      {/* Two stations named alike (KITCHEN and COZINHA): the code tells them apart, on screen and read out. */}
      {withCode && <>{' '}<span className="font-mono text-xs">{code}</span></>}
    </>
  );
}
