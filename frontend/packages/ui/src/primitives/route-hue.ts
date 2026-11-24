/**
 * The one source of a production route's hue (DESIGN.md "Light reference", "Route chips"). Destination and station
 * codes are the tenant's (`^[A-Z][A-Z0-9_]*$`); the conventional ones, in either language, take their route's hue and
 * any other the neutral surface. The hue is a border and a 15 % tint, the text stays `foreground`: light text on
 * these hues is below AA. The label is the caller's: this names the colour only.
 */
const FILL = {
  kitchen: 'border-route-kitchen bg-route-kitchen/15',
  bar: 'border-route-bar bg-route-bar/15',
  copa: 'border-route-copa bg-route-copa/15',
} as const;

const DOT: Record<keyof typeof FILL, string> = {
  kitchen: 'bg-route-kitchen',
  bar: 'bg-route-bar',
  copa: 'bg-route-copa',
};

/** The conventional codes and their route. COZINHA is the kitchen, as KITCHEN; PANTRY is the copa. */
export const ROUTE_HUES: Readonly<Record<string, keyof typeof FILL>> = {
  KITCHEN: 'kitchen',
  COZINHA: 'kitchen',
  BAR: 'bar',
  COPA: 'copa',
  PANTRY: 'copa',
};

const hueOf = (code: string | null | undefined) => (code ? ROUTE_HUES[code] : undefined);

/** A route chip's fill: the hue's border and tint with `foreground` text; an unknown or absent code, the neutral surface. */
export function routeChipFill(code: string | null | undefined): string {
  const hue = hueOf(code);
  return `${hue ? FILL[hue] : 'border-border bg-muted'} text-foreground`;
}

/** The route's hue alone, for a decorative dot beside its name; an unknown code, the secondary text colour. */
export function routeDot(code: string | null | undefined): string {
  const hue = hueOf(code);
  return hue ? DOT[hue] : 'bg-muted-foreground';
}
