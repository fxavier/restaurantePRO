import { Badge, routeChipFill, routeLabel } from '@restaurantpro/ui';

/**
 * Where an item is prepared (the catalog's `productionDestination`), as its route chip (DESIGN.md "Route chips"): the
 * shared route hue and name (`routeChipFill`, `routeLabel`: COZINHA the kitchen's) as a border and tint with
 * `foreground` text, any other code a tenant chose as itself; nothing when it is not prepared.
 */
export function DestinationBadge({ code }: { code: string | null | undefined }) {
  if (!code) return null;
  return (
    <Badge variant="secondary" className={routeChipFill(code)}>
      {routeLabel(code)}
    </Badge>
  );
}
